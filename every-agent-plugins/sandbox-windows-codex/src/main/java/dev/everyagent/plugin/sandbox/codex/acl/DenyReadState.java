package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * deny-read ACL 跨运行状态——对齐 codex {@code deny_read_state.rs}。
 *
 * <p>workspace-write / elevated 会话在命令退出后<b>故意保留 ACL</b>（子孙进程可能活得比
 * launcher 久）→ deny-read ACL 集合跨运行有状态。持久化
 * {@code <stateRoot>/deny_read_acl_state.json}（按 principal SID 键分区，elevated 主体
 * 是沙箱组 SID；serde BTreeMap → TreeMap 排序一致），对账顺序：
 * <b>先 apply 新目标集</b>（顺序保证撤销前总有 deny 生效）→ 后对旧集合差集
 * revoke（profile 变更不留陈旧 deny）→ upsert/移除主体 → 原子写回。
 *
 * <p>原子写：同目录临时文件 + {@code ATOMIC_MOVE}（不支持时回退 REPLACE_EXISTING）。
 */
public final class DenyReadState {

    /** 状态文件名（codex DENY_READ_ACL_STATE_FILE）。 */
    public static final String STATE_FILE = "deny_read_acl_state.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    private DenyReadState() {
    }

    /** 状态文件路径。 */
    public static Path stateFile(Path stateRoot) {
        return stateRoot.resolve(STATE_FILE);
    }

    /**
     * 对账一个主体的持久 deny-read ACL 集（对齐 {@code sync_persistent_deny_read_acls}）。
     *
     * @param stateRoot    状态目录（codexHome/.sandbox；不存在则创建）
     * @param principalSid 主体 SID 字符串（状态分区键）
     * @param desiredPaths 新目标集（词法/缺失路径均可——物化在 apply 内完成）
     * @param ops          ACE 操作（Windows 实现或测试假件）
     * @return 实际生效的全部计划路径（含幂等跳过的既有目标）
     */
    public static List<Path> sync(Path stateRoot, String principalSid, List<Path> desiredPaths,
            AclOperations ops) throws IOException {
        Path file = stateFile(stateRoot);
        Files.createDirectories(stateRoot);
        Map<String, List<String>> state = load(file);
        List<String> previous = state.getOrDefault(principalSid, List.of());

        List<Path> applied = DenyReadPlanner.apply(desiredPaths, principalSid, ops);
        Set<String> desiredKeys = new HashSet<>();
        for (Path path : applied) {
            desiredKeys.add(DenyReadPlanner.lexicalPathKey(path));
        }
        for (String previousPath : previous) {
            if (!desiredKeys.contains(DenyReadPlanner.lexicalPathKey(Path.of(previousPath)))) {
                try {
                    ops.revokeAce(Path.of(previousPath), principalSid); // 陈旧 deny：尽力撤销
                } catch (IOException | RuntimeException suppressed) {
                    // 对齐 codex：let _ = revoke_ace(...)——撤销失败不阻断状态落盘
                }
            }
        }

        if (applied.isEmpty()) {
            state.remove(principalSid);
        } else {
            List<String> appliedStrings = new ArrayList<>();
            for (Path path : applied) {
                appliedStrings.add(path.toString());
            }
            state.put(principalSid, appliedStrings);
        }
        store(file, state);
        return applied;
    }

    /** 读状态（文件缺失 = 空态；损坏 = 报错——不静默重置跨运行 ACL 账本）。 */
    static Map<String, List<String>> load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new TreeMap<>();
        }
        JsonNode root = JSON.readTree(Files.readString(file));
        Map<String, List<String>> principals = new TreeMap<>();
        if (root.has("principals")) {
            JsonNode principalsNode = root.get("principals");
            principalsNode.fieldNames().forEachRemaining(sid -> {
                List<String> paths = new ArrayList<>();
                principalsNode.get(sid).forEach(item -> paths.add(item.asText()));
                principals.put(sid, paths);
            });
        }
        return principals;
    }

    /** pretty JSON 原子写（principals 键排序，对齐 serde BTreeMap 输出顺序）。 */
    static void store(Path file, Map<String, List<String>> state) throws IOException {
        Map<String, List<String>> sorted = new TreeMap<>(state);
        String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("principals", sorted));
        Path temp = Files.createTempFile(file.getParent(), STATE_FILE, ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temp, json);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            }
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }
}
