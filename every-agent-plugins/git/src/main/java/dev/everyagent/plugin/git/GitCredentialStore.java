package dev.everyagent.plugin.git;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.util.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * git 远端凭证加密存储(架构 §7.12 补充;见 docs/ARCHITECTURE.md §7.12):
 * 用户在前端输入后经 git.credential.save 落盘为 <workspace>/.everyagent/.git-credentials.enc,
 * AES-256-GCM 加密(每次随机 IV,AAD=host 绑定条目防串换),密钥按工作区独立:首次
 * 在该工作区保存凭证时自动生成——不要求用户记忆主密码,换机后旧密文即失效,重新输入即可。
 *
 * <p><b>主密钥存放(安全整改 M-11)</b>:旧版把 base64 明文主密钥存
 * {@code <workspaceRoot>/.everyagent/.git-credential.key} 且与密文同级,能拷走整个工作区
 * 目录的人(备份/同步盘/泄露)同时拿到"密文+钥匙",加密退化为混淆。现改为:
 * <ul>
 *   <li><b>Windows(常规路径)</b>:主密钥字节经 DPAPI {@code CryptProtectData}
 *       <b>当前用户作用域</b>包裹后,以 v2 信封 JSON 写回工作区同路径——信封离开
 *       本机/本用户即不可解。<b>选用户作用域而非机器作用域</b>(与 sandbox-windows-codex
 *       先例相反):git 凭证是用户级秘密,worker 全程以用户身份加解密,不存在
 *       "提权 helper 加密、非提权 runner 解密"的跨账户需求;用户作用域还能挡住同机
 *       其他账户解密,暴露面更小。</li>
 *   <li><b>非 Windows / DPAPI 不可用(回退)</b>:主密钥外置到工作区之外
 *       {@code <home>/git-credential-keys/<sha256(工作区真实路径)>.key}
 *       (home = EVERYAGENT_HOME 或 ~/.everyagent),POSIX chmod 600、Windows 尽力
 *       仅保留 owner ACE——拷走工作区目录不再包含任何密钥材料。</li>
 * </ul>
 * 旧版明文 key 在首次需要主密钥时自动一次性迁移(包裹/外置落盘 → 读回 + 旧密文
 * 全条目解密验证 → 删旧文件;幂等,失败不删旧文件、本次降级直用旧钥)。主密钥值
 * 迁移前后不变,仅换存放形态;条目级 AES-GCM 逻辑不变。
 *
 * <p>明文账号密码仅存在于 worker 进程内存;磁盘上只有密文。文件按 workspace 隔离
 * (各工作区各自的 .everyagent/.git-credentials.enc),同一工作区内按 host 索引多账号。
 */
public class GitCredentialStore {

    private static final Logger log = LoggerFactory.getLogger(GitCredentialStore.class);
    private static final String FILE_NAME = ".git-credentials.enc";
    /** 工作区内主密钥文件名(旧版裸 base64 明文与新版 DPAPI 信封同路径,靠内容形态区分)。 */
    private static final String KEY_FILE_NAME = ".git-credential.key";
    /** 外置回退时密钥目录名(位于 <home> 下,home = EVERYAGENT_HOME 或 ~/.everyagent)。 */
    private static final String OUTSIDE_KEY_DIR = "git-credential-keys";
    /** v2 信封 kind:DPAPI 当前用户作用域包裹。 */
    private static final String KIND_DPAPI_USER = "dpapi-user";
    /** v2 信封 kind:裸密钥字节(仅用于工作区外置文件,文件本身 600/owner-only)。 */
    private static final String KIND_RAW = "raw";
    private static final int GCM_TAG_BITS = 128;
    /** DPAPI 可用性自测载荷(加解 roundtrip 通过才启用 DPAPI 路径)。 */
    private static final byte[] DPAPI_PROBE =
            "everyagent-git-credential-dpapi-probe".getBytes(StandardCharsets.UTF_8);

    /** 按工作区缓存各自独立的 AES-256 密钥(key = workspaceRoot 的 Path,懒加载于首用)。 */
    private final Map<Path, SecretKey> keys = new ConcurrentHashMap<>();

    /** 外置密钥根目录(生产 = EVERYAGENT_HOME 或 ~/.everyagent;测试注入临时目录)。 */
    private final Path homeDir;

    /** 测试注入:强制禁用 DPAPI,走外置回退路径(验证非 Windows 布局)。 */
    private final boolean dpapiDisabled;

    /** DPAPI 可用性探测缓存(见 {@link #dpapiAvailable()})。 */
    private static volatile Boolean dpapiAvailableCache;

    public GitCredentialStore() {
        this(null, false);
    }

    /** 测试构造:注入外置 home 目录与"禁用 DPAPI 强制走外置回退"开关。 */
    GitCredentialStore(Path homeDirOverride, boolean dpapiDisabled) {
        this.homeDir = homeDirOverride != null ? homeDirOverride : defaultHomeDir();
        this.dpapiDisabled = dpapiDisabled;
    }

    private static Path defaultHomeDir() {
        String override = System.getenv("EVERYAGENT_HOME");
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        return Path.of(System.getProperty("user.home"), ".everyagent");
    }

    // ---- 对外 API ----

    /** 保存(或覆盖)某 host 的凭证,加密落盘工作区。 */
    public synchronized void save(Path workspaceRoot, String host, String username, String password) {
        try {
            byte[] keyBytes = loadKey(workspaceRoot).getEncoded();
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(host.getBytes(StandardCharsets.UTF_8));
            byte[] plain = (username + "\u0000" + password).getBytes(StandardCharsets.UTF_8);
            byte[] enc = cipher.doFinal(plain);

            Path file = fileOf(workspaceRoot);
            Files.createDirectories(file.getParent());
            ObjectNode root = Files.isRegularFile(file) ? readJson(file) : Json.obj();
            root.put("version", 1);
            ObjectNode entries = root.path("entries").isObject()
                    ? (ObjectNode) root.path("entries") : root.putObject("entries");
            entries.set(host, Json.obj()
                    .put("iv", Base64.getEncoder().encodeToString(iv))
                    .put("cipher", Base64.getEncoder().encodeToString(enc))
                    .put("ts", System.currentTimeMillis()));
            atomicWrite(file, Json.write(root));
        } catch (Exception e) {
            throw new RuntimeException("凭证加密保存失败: " + e.getMessage(), e);
        }
    }

    /** 读取某 host 的凭证(解密密文);无条目或解密失败返回 empty。 */
    public Optional<UsernamePassword> load(Path workspaceRoot, String host) {
        Path file = fileOf(workspaceRoot);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            ObjectNode root = readJson(file);
            if (!root.path("entries").isObject()) {
                return Optional.empty();
            }
            ObjectNode entries = (ObjectNode) root.path("entries");
            JsonNode entry = entries.path(host);
            if (entry.isMissingNode()) {
                return Optional.empty();
            }
            byte[] keyBytes = loadKey(workspaceRoot).getEncoded();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, Base64.getDecoder().decode(entry.path("iv").asString())));
            cipher.updateAAD(host.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(entry.path("cipher").asString()));
            String text = new String(plain, StandardCharsets.UTF_8);
            int sep = text.indexOf('\u0000');
            if (sep < 0) {
                return Optional.empty();
            }
            return Optional.of(new UsernamePassword(text.substring(0, sep), text.substring(sep + 1)));
        } catch (Exception e) {
            // 密钥不匹配/条目损坏/被篡改(AAD 校验失败):按无凭证处理,触发重新输入。
            log.warn("git 凭证解密失败 host={}(按无凭证处理): {}", host, e.getMessage());
            return Optional.empty();
        }
    }

    /** 是否已存有某 host 的凭证(供前端展示/静默判定)。 */
    public boolean has(Path workspaceRoot, String host) {
        Path file = fileOf(workspaceRoot);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            return readJson(file).path("entries").has(host);
        } catch (Exception e) {
            return false;
        }
    }

    /** 明文用户名/密码对(仅 worker 内存,经 askpass env 注入 git 子进程用)。 */
    public record UsernamePassword(String username, String password) {
    }

    // ---- 内部 ----

    /** 工作区内主密钥文件路径(旧版明文与新版 DPAPI 信封同一路径)。 */
    private Path workspaceKeyPath(Path workspaceRoot) {
        return workspaceRoot.resolve(".everyagent").resolve(KEY_FILE_NAME);
    }

    /** 外置回退时主密钥文件:<home>/git-credential-keys/<sha256(工作区真实路径)>.key(测试可见)。 */
    Path outsideKeyFile(Path workspaceRoot) {
        return homeDir.resolve(OUTSIDE_KEY_DIR).resolve(sha256Hex(realPath(workspaceRoot)) + ".key");
    }

    Path fileOf(Path workspaceRoot) {
        return workspaceRoot.resolve(".everyagent").resolve(FILE_NAME);
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (Exception notExisting) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String sha256Hex(Path path) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(path.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private ObjectNode readJson(Path file) throws java.io.IOException {
        JsonNode node = Json.parse(Files.readString(file));
        return node.isObject() ? (ObjectNode) node : Json.obj();
    }

    private void atomicWrite(Path file, String content) throws java.io.IOException {
        AtomicFiles.writeText(file, content); // 唯一名 tmp + 原子替换(失败已清理,不残留垃圾)
    }

    // ---- 主密钥存取(M-11 整改核心) ----

    /**
     * 自动生成/加载某工作区的 AES-256 主密钥(存放形态见类注释):
     * ① 新形态信封已存在 → 载入(顺带收敛清理工作区内旧明文残留);
     * ② 旧版明文 key(整文件裸 base64)→ 一次性迁移:包裹/外置落盘 → 读回校验 +
     *    旧密文全条目解密验证 → 删旧文件;任何一步失败不删旧文件、本次降级直用旧钥;
     * ③ 首次使用 → 生成 256-bit 密钥按新形态落盘。
     * 密钥文件失真(垃圾/来自其他用户或机器的 DPAPI blob)→ 自愈重生成,
     * 旧密文按无凭证处理(重新录入),与"换机后旧密文失效重输"的既有语义一致。
     */
    private synchronized SecretKey loadKey(Path workspaceRoot) throws Exception {
        SecretKey cached = keys.get(workspaceRoot);
        if (cached != null) {
            return cached;
        }
        boolean dpapi = dpapiUsable();
        Path legacyKeyFile = workspaceKeyPath(workspaceRoot);
        Path keyFile = dpapi ? legacyKeyFile : outsideKeyFile(workspaceRoot);

        // ① 新形态信封
        if (Files.isRegularFile(keyFile)) {
            try {
                byte[] keyBytes = readKeyEnvelope(keyFile, dpapi);
                cleanupLegacyResidue(legacyKeyFile, keyFile, keyBytes);
                return cacheKey(workspaceRoot, keyBytes);
            } catch (Exception corrupt) {
                log.warn("git 凭证主密钥文件失真(损坏或来自其他用户/机器),将自愈重生成"
                        + "(旧密文按无凭证处理,需重新录入): {} ({})", keyFile, corrupt.getMessage());
                // 落到 ②/③:或许还能从旧版明文迁移恢复,否则重生成覆盖
            }
        }

        // ② 旧版明文 key(整文件 base64)
        if (Files.isRegularFile(legacyKeyFile)) {
            byte[] legacyKeyBytes;
            try {
                legacyKeyBytes = decodeLegacyKey(Files.readString(legacyKeyFile));
            } catch (Exception notALegacyKey) {
                log.warn("旧版 git 凭证主密钥文件内容失真,自愈重生成: {} ({})",
                        legacyKeyFile, notALegacyKey.getMessage());
                SecretKey fresh = generateAndStore(workspaceRoot, keyFile, dpapi);
                cleanupLegacyResidue(legacyKeyFile, keyFile, fresh.getEncoded());
                return fresh;
            }
            try {
                storeKeyEnvelope(keyFile, legacyKeyBytes, dpapi);
                byte[] reloaded = readKeyEnvelope(keyFile, dpapi);
                if (!Arrays.equals(reloaded, legacyKeyBytes)) {
                    throw new IllegalStateException("迁移后主密钥读回不一致");
                }
                if (!verifyCiphertextWithKey(workspaceRoot, legacyKeyBytes)) {
                    throw new IllegalStateException("旧密文无法用待迁移主密钥解开");
                }
                if (!keyFile.equals(legacyKeyFile)) {
                    Files.deleteIfExists(legacyKeyFile);
                }
                log.info("git 凭证主密钥已迁移为{}(主密钥值不变): {}",
                        dpapi ? "DPAPI 包裹(当前用户作用域)" : "工作区外置", keyFile);
            } catch (Exception migrationFailed) {
                // 幂等:旧文件保留,本次进程内降级直用旧钥,下次自动重试。
                // (Windows 同路径形态下"包裹落盘"即原子替换旧明文——密钥值已读回校验相等,
                //  即使旧密文验证失败也未丢失任何可恢复材料。)
                log.error("git 凭证主密钥迁移失败,旧文件保留、本次降级直用旧钥: {} ({})",
                        legacyKeyFile, migrationFailed.getMessage());
            }
            return cacheKey(workspaceRoot, legacyKeyBytes);
        }

        // ③ 首次使用
        return generateAndStore(workspaceRoot, keyFile, dpapi);
    }

    private SecretKey generateAndStore(Path workspaceRoot, Path keyFile, boolean dpapi) throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        byte[] keyBytes = kg.generateKey().getEncoded();
        storeKeyEnvelope(keyFile, keyBytes, dpapi);
        log.info("已自动生成 git 凭证加密主密钥({}): {}",
                dpapi ? "DPAPI 当前用户包裹" : "工作区外置", keyFile);
        return cacheKey(workspaceRoot, keyBytes);
    }

    private SecretKey cacheKey(Path workspaceRoot, byte[] keyBytes) {
        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        keys.put(workspaceRoot, key);
        return key;
    }

    /** 内容是否为 v2 主密钥信封(与旧版"整文件裸 base64"形态互斥)。 */
    private static boolean isKeyEnvelope(String content) {
        try {
            JsonNode node = Json.parse(content);
            return node.isObject() && node.path("v").asInt(0) == 2 && node.has("k") && node.has("d");
        } catch (Exception notJson) {
            return false;
        }
    }

    /** 读 v2 信封还原主密钥字节(dpapi-user → DPAPI 当前用户解包;raw → base64 直取)。 */
    private static byte[] readKeyEnvelope(Path keyFile, boolean dpapi) throws Exception {
        String content = Files.readString(keyFile);
        if (!isKeyEnvelope(content)) {
            throw new IllegalStateException("主密钥文件不是 v2 信封: " + keyFile);
        }
        JsonNode node = Json.parse(content);
        String kind = node.path("k").asString();
        byte[] data = Base64.getDecoder().decode(node.path("d").asString().trim());
        byte[] keyBytes;
        if (KIND_DPAPI_USER.equals(kind)) {
            if (!dpapi) {
                throw new IllegalStateException("DPAPI 不可用,无法解开 dpapi-user 主密钥信封: " + keyFile);
            }
            keyBytes = Dpapi.unprotect(data);
        } else if (KIND_RAW.equals(kind)) {
            keyBytes = data;
        } else {
            throw new IllegalStateException("未知主密钥信封类型: " + kind);
        }
        requireAesKey(keyBytes);
        return keyBytes;
    }

    /** 主密钥按当前方案落盘:Windows=DPAPI 包裹信封(写前 roundtrip 自校验);否则 raw 信封外置+权限收紧。 */
    private static void storeKeyEnvelope(Path keyFile, byte[] keyBytes, boolean dpapi) throws Exception {
        byte[] payload = keyBytes;
        String kind = KIND_RAW;
        if (dpapi) {
            payload = Dpapi.protect(keyBytes);
            kind = KIND_DPAPI_USER;
            if (!Arrays.equals(Dpapi.unprotect(payload), keyBytes)) {
                throw new IllegalStateException("DPAPI 包裹自校验失败");
            }
        }
        Files.createDirectories(keyFile.getParent());
        ObjectNode envelope = Json.obj().put("v", 2).put("k", kind)
                .put("d", Base64.getEncoder().encodeToString(payload));
        AtomicFiles.writeText(keyFile, Json.write(envelope));
        if (!dpapi) {
            hardenSecretPath(keyFile.getParent(), true);
            hardenSecretPath(keyFile, false);
        }
    }

    /** 旧版主密钥文件内容 = 整文件 base64(AES 合法长度);其余视为失真。 */
    private static byte[] decodeLegacyKey(String content) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(content.trim());
        requireAesKey(decoded);
        return decoded;
    }

    private static void requireAesKey(byte[] keyBytes) {
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalArgumentException("非法 AES 密钥长度: " + keyBytes.length);
        }
    }

    /**
     * ①命中新形态后收敛清理工作区内旧明文残留(上次迁移"写新成功、删旧失败"的收敛):
     * 残留与现行主密钥一致或是垃圾 → 删除;是另一把合法密钥 → 保留并告警(人工裁决)。
     */
    private static void cleanupLegacyResidue(Path legacyKeyFile, Path keyFile, byte[] activeKeyBytes) {
        if (keyFile.equals(legacyKeyFile) || !Files.isRegularFile(legacyKeyFile)) {
            return;
        }
        try {
            byte[] legacy = decodeLegacyKey(Files.readString(legacyKeyFile));
            if (Arrays.equals(legacy, activeKeyBytes)) {
                Files.deleteIfExists(legacyKeyFile);
                log.info("已清理工作区内旧版明文主密钥残留: {}", legacyKeyFile);
            } else {
                log.warn("工作区内旧版明文主密钥与现行主密钥不一致,保留待人工处理: {}", legacyKeyFile);
            }
        } catch (Exception garbageOrUnreadable) {
            try {
                Files.deleteIfExists(legacyKeyFile);
            } catch (Exception ignore) {
                // 清理失败不影响功能,下次重试
            }
        }
    }

    /**
     * 迁移前验证:候选主密钥必须能解开密文文件中全部条目(AEAD tag 即完整性证明);
     * 密文缺失/无条目/整体不可解析 → 放行(无可验证项)。
     */
    private static boolean verifyCiphertextWithKey(Path workspaceRoot, byte[] keyBytes) {
        Path file = workspaceRoot.resolve(".everyagent").resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return true;
        }
        JsonNode entries;
        try {
            entries = Json.parse(Files.readString(file)).path("entries");
        } catch (Exception unreadable) {
            log.warn("git 凭证密文文件不可解析,迁移验证跳过(密文自身已不可用): {}", file);
            return true;
        }
        if (!entries.isObject() || entries.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, JsonNode> entry : entries.properties()) {
            String host = entry.getKey();
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                        new GCMParameterSpec(GCM_TAG_BITS,
                                Base64.getDecoder().decode(entry.getValue().path("iv").asString())));
                cipher.updateAAD(host.getBytes(StandardCharsets.UTF_8));
                cipher.doFinal(Base64.getDecoder().decode(entry.getValue().path("cipher").asString()));
            } catch (Exception decryptFailed) {
                log.error("旧密文条目无法用待迁移主密钥解开 host={}: {}", host, decryptFailed.getMessage());
                return false;
            }
        }
        return true;
    }

    /** 外置密钥文件/目录权限收紧(best-effort):POSIX 600/700;NTFS 尽力仅保留 owner ACE。 */
    private static void hardenSecretPath(Path path, boolean directory) {
        try {
            Files.setPosixFilePermissions(path,
                    PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        } catch (Exception posixUnsupported) {
            // 非 POSIX 文件系统 → 尝试 ACL 视图
        }
        try {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view == null) {
                return;
            }
            UserPrincipal owner = view.getOwner();
            List<AclEntry> ownerOnly = new ArrayList<>();
            for (AclEntry ace : view.getAcl()) {
                if (owner.equals(ace.principal())) {
                    ownerOnly.add(ace);
                }
            }
            if (!ownerOnly.isEmpty()) {
                view.setAcl(ownerOnly);
            }
        } catch (Exception aclUnsupported) {
            log.debug("外置主密钥权限收紧不可用({}),退回目录默认权限: {}",
                    aclUnsupported.getMessage(), path);
        }
    }

    /** 本实例是否走 DPAPI 包裹:未被测试禁用且运行环境 DPAPI 可用。 */
    private boolean dpapiUsable() {
        return !dpapiDisabled && dpapiAvailable();
    }

    /** DPAPI 是否可用(Windows + JNA 就绪 + 用户作用域 roundtrip 自测通过);探测一次后缓存,测试可见。 */
    static boolean dpapiAvailable() {
        Boolean cached = dpapiAvailableCache;
        if (cached != null) {
            return cached;
        }
        synchronized (GitCredentialStore.class) {
            if (dpapiAvailableCache == null) {
                try {
                    byte[] blob = Dpapi.protect(DPAPI_PROBE);
                    dpapiAvailableCache = Arrays.equals(Dpapi.unprotect(blob), DPAPI_PROBE);
                } catch (Throwable unavailable) {
                    // 非 Windows / JNA 缺失 / DPAPI 异常 → 外置回退,不影响插件加载
                    dpapiAvailableCache = Boolean.FALSE;
                }
            }
            return dpapiAvailableCache;
        }
    }

    /**
     * DPAPI 包装(Windows 专属)。JNA 仅在此嵌套类内引用且懒加载:缺 JNA 或非 Windows 时
     * 首次触碰抛错,由 {@link #dpapiAvailable()} 吞 Throwable 落到外置回退,不影响外层类加载。
     * <p>flags 只带 {@code CRYPTPROTECT_UI_FORBIDDEN}(无桌面交互)<b>不带</b>
     * {@code CRYPTPROTECT_LOCAL_MACHINE}——当前用户作用域,理由见类注释。
     */
    private static final class Dpapi {
        private Dpapi() {
        }

        static byte[] protect(byte[] data) {
            return com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(data,
                    com.sun.jna.platform.win32.WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
        }

        static byte[] unprotect(byte[] blob) {
            return com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(blob,
                    com.sun.jna.platform.win32.WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
        }
    }
}
