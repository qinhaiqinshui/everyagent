package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 读授权（read root 组 ACE）的跨运行账本 —— 补上「读授权无法按根门控」的回收缺口。
 *
 * <p><b>为什么读授权不能像写那样靠 capability SID 门控</b>：capability SID 是以
 * <b>restricting SID</b>（{@code CreateRestrictedToken} + {@code WRITE_RESTRICTED}）进令牌的，
 * 而 restricting 列表<b>只参与写访问检查</b>；读检查看的是令牌的普通组。若把读 ACE 授给
 * cap SID，读检查根本匹配不到 → 直接读不了。故读授权只能授给<b>沙箱组 SID</b>，
 * 而组 SID 恒在令牌里 → 撤销后 ACE 若留着，读权限就留着。
 *
 * <p><b>因此读回收必须物理撤 ACE</b>：本类持久化「该主体施过读授权的路径」
 * （{@code <stateRoot>/read_grants_state.json}，按 principal SID 分区），每次 preflight 对账：
 * <b>先按当前目标集补齐/确认</b> → <b>再对本轮不再是读根的旧路径 {@code revokeAce}</b>
 * （随后 preflight 在会话启动前完成，故下一条命令即被系统级拒绝）。结构对齐
 * {@code DenyReadState}（同款 JSON 形态与原子写，复用其 loader/store）。
 *
 * <p><b>两条保护（避免误删别人的 ACE）</b>：
 * <ol>
 *   <li>{@code neverRevokeKeys} 内的路径只「遗忘」不撤 ACE——两类来源：<b>当前写根</b>
 *       （写 ACE = 组 + cap 双主体，撤掉组 ACE 会连带丢掉读；且写根升级自读根是正常路径），
 *       与<b>当前 deny-read 目标</b>（同一路径上同一 SID 还有 deny ACE，
 *       {@code revokeAce} 会删该 SID 的全部显式 ACE → 连带删掉 deny，反而放宽）；</li>
 *   <li>系统本就放行读（Everyone/Users/Authenticated Users 已具 RX）的路径<b>不入账</b>
 *       ——我们没施加任何东西，也就无从撤起。</li>
 * </ol>
 *
 * <p>撤销失败只记日志不阻断（对齐 codex {@code let _ = revoke_ace(...)}）。
 */
public final class ReadGrantState {

    /** 状态文件名。 */
    public static final String STATE_FILE = "read_grants_state.json";

    private ReadGrantState() {
    }

    /** 状态文件路径。 */
    public static Path stateFile(Path stateRoot) {
        return stateRoot.resolve(STATE_FILE);
    }

    /**
     * 对账一个主体的读授权账本。
     *
     * @param stateRoot       状态目录（codexHome/.sandbox；不存在则创建）
     * @param principalSid    主体 SID（沙箱组 SID；状态分区键）
     * @param desiredPaths    本轮<b>实际持有读授权</b>的路径（含本次新增与既有确认）
     * @param neverRevokeKeys 只遗忘不撤销的路径词法键（当前写根 + 当前 deny-read 目标）
     * @param ops             ACE 操作（Windows 实现或测试假件）
     */
    public static void sync(Path stateRoot, String principalSid, List<Path> desiredPaths,
            Set<String> neverRevokeKeys, AclOperations ops) throws IOException {
        Path file = stateFile(stateRoot);
        Files.createDirectories(stateRoot);
        Map<String, List<String>> state = DenyReadState.load(file);
        List<String> previous = state.getOrDefault(principalSid, List.of());

        Set<String> desiredKeys = new HashSet<>();
        for (Path path : desiredPaths) {
            desiredKeys.add(DenyReadPlanner.lexicalPathKey(path));
        }

        Set<String> neverRevoke = neverRevokeKeys == null ? Set.of() : neverRevokeKeys;
        for (String previousPath : previous) {
            String key = DenyReadPlanner.lexicalPathKey(Path.of(previousPath));
            if (desiredKeys.contains(key) || neverRevoke.contains(key)) {
                continue; // 仍持有 → 保留；受保护 → 只遗忘（不撤 ACE）
            }
            try {
                ops.revokeAce(Path.of(previousPath), principalSid); // 陈旧读授权：物理回收
            } catch (IOException | RuntimeException suppressed) {
                // 对齐 codex：撤销失败不阻断状态落盘（下一次 preflight 自然重试）
            }
        }

        if (desiredPaths.isEmpty()) {
            state.remove(principalSid);
        } else {
            List<String> applied = new ArrayList<>();
            for (Path path : desiredPaths) {
                applied.add(path.toString());
            }
            state.put(principalSid, applied);
        }
        DenyReadState.store(file, state);
    }
}