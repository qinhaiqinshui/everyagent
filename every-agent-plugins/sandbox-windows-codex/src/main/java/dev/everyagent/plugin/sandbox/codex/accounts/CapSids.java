package dev.everyagent.plugin.sandbox.codex.accounts;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 合成 capability SID 集合与持久化（对应 codex cap.rs，设计文档 §2.2 CapSidStore）。
 *
 * <p>capability SID 是随机合成的 {@code S-1-5-21-{a}-{b}-{c}-{d}} group SID（4 个随机
 * subauthority，不对应任何真实账户）——挂进令牌组/restricting 列表后即可按它在 ACL 上
 * 授权或拒止。零 AppContainer 依赖（分析文档 §5）。
 *
 * <p>JSON 落盘 {@code <codexHome>/cap_sid}，键名对齐 cap.rs：
 * {@code workspace}/{@code readonly}/{@code workspace_by_cwd}/{@code writable_root_by_path}；
 * 兼容旧版「裸 SID 文本」格式（非 {@code {} 开头则当作 workspace 值，新造 readonly 并重写为 JSON）。
 */
public final class CapSids {

    /** capability SID 形态（S-1-5-21 + 4 个无符号 subauthority）。 */
    public static final Pattern CAP_SID_PATTERN =
            Pattern.compile("S-1-5-21-\\d+-\\d+-\\d+-\\d+");

    private static final SecureRandom RNG = new SecureRandom();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 全局共享的 workspace capability（默认键，codex 首版语义）。 */
    @JsonProperty("workspace")
    public String workspace;
    /** 全机共享的只读会话 capability。 */
    @JsonProperty("readonly")
    public String readonly;
    /** 按工作区 canonical cwd 键控的隔离 capability（防止跨工作区写扩散）。 */
    @JsonProperty("workspace_by_cwd")
    public Map<String, String> workspaceByCwd = new LinkedHashMap<>();
    /** 按额外可写根 canonical 路径键控的 capability（过期根不进后续令牌）。 */
    @JsonProperty("writable_root_by_path")
    public Map<String, String> writableRootByPath = new LinkedHashMap<>();

    /** 生成一个随机 capability SID 字符串（对齐 make_random_cap_sid_string）。 */
    public static String randomCapSid() {
        return "S-1-5-21-" + unsigned(RNG.nextInt()) + "-" + unsigned(RNG.nextInt()) + "-"
                + unsigned(RNG.nextInt()) + "-" + unsigned(RNG.nextInt());
    }

    /** cap_sid 持久化文件路径（对齐 cap_sid_file：codex_home/cap_sid，无扩展名）。 */
    public static Path capSidFile(Path codexHome) {
        return codexHome.resolve("cap_sid");
    }

    /**
     * 读取或创建整套 capability SID（对齐 load_or_create_cap_sids）：
     * JSON 直接解析；旧版裸 SID 文本兼容为 workspace 值；损坏/缺失全量重建。
     * 每次新增键后 {@link #persist} 全量重写。
     */
    public static synchronized CapSids loadOrCreate(Path codexHome) throws IOException {
        Path file = capSidFile(codexHome);
        if (Files.exists(file)) {
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (text.startsWith("{") && text.endsWith("}")) {
                try {
                    CapSids caps = MAPPER.readValue(text, CapSids.class);
                    if (caps != null && caps.workspaceByCwd == null) {
                        caps.workspaceByCwd = new LinkedHashMap<>();
                    }
                    if (caps != null && caps.writableRootByPath == null) {
                        caps.writableRootByPath = new LinkedHashMap<>();
                    }
                    if (caps != null && caps.workspace != null && caps.readonly != null) {
                        return caps;
                    }
                } catch (IOException ignored) {
                    // 落到重建分支（对齐 codex：解析失败即全量重建）
                }
            } else if (!text.isEmpty() && CAP_SID_PATTERN.matcher(text).matches()) {
                CapSids caps = new CapSids();
                caps.workspace = text;
                caps.readonly = randomCapSid();
                caps.persist(file);
                return caps;
            }
        }
        CapSids caps = new CapSids();
        caps.workspace = randomCapSid();
        caps.readonly = randomCapSid();
        caps.persist(file);
        return caps;
    }

    /** 取（惰性创建并持久化）按 cwd 隔离的 workspace capability（对齐 workspace_cap_sid_for_cwd）。 */
    public static synchronized String workspaceCapSidForCwd(Path codexHome, Path cwd)
            throws IOException {
        CapSids caps = loadOrCreate(codexHome);
        String key = canonicalPathKey(cwd);
        String sid = caps.workspaceByCwd.get(key);
        if (sid != null) {
            return sid;
        }
        sid = randomCapSid();
        caps.workspaceByCwd.put(key, sid);
        caps.persist(capSidFile(codexHome));
        return sid;
    }

    /** 取（惰性创建并持久化）额外可写根的 capability（对齐 writable_root_cap_sid_for_path）。 */
    public static synchronized String writableRootCapSidForPath(Path codexHome, Path root)
            throws IOException {
        CapSids caps = loadOrCreate(codexHome);
        String key = canonicalPathKey(root);
        String sid = caps.writableRootByPath.get(key);
        if (sid != null) {
            return sid;
        }
        sid = randomCapSid();
        caps.writableRootByPath.put(key, sid);
        caps.persist(capSidFile(codexHome));
        return sid;
    }

    /** 统一入口：root==cwd 用 workspace_by_cwd 键，否则用 writable_root_by_path 键。 */
    public static String workspaceWriteCapSidForRoot(Path codexHome, Path cwd, Path root)
            throws IOException {
        if (canonicalPathKey(root).equals(canonicalPathKey(cwd))) {
            return workspaceCapSidForCwd(codexHome, cwd);
        }
        return writableRootCapSidForPath(codexHome, root);
    }

    /**
     * canonical 路径键（对齐 canonical_path_key）：realpath（不存在则绝对规范化）→
     * 反斜杠归一为 {@code /} → 小写（Windows 大小写不敏感语义）。
     */
    public static String canonicalPathKey(Path path) {
        Path canonical;
        try {
            canonical = path.toRealPath();
        } catch (IOException e) {
            canonical = path.toAbsolutePath().normalize();
        }
        return canonical.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    /** 全量重写持久化（对齐 persist_caps；原子替换，失败退回直接写）。 */
    public void persist(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        byte[] json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(this);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, json);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicUnsupported) {
            Files.write(file, json);
            Files.deleteIfExists(tmp);
        }
    }

    private static String unsigned(int v) {
        return Integer.toUnsignedString(v);
    }
}
