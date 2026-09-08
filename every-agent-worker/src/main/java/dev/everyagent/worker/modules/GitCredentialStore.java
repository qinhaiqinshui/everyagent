package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.AtomicFiles;
import dev.everyagent.worker.config.WorkerProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * git 远端凭证加密存储(架构 §7.12 补充;见 docs/ARCHITECTURE.md §7.12):
 * 用户在前端输入后经 git.credential.save 落盘为 <workspace>/.everyagent/.git-credentials.enc,
 * AES-256-GCM 加密(每次随机 IV,AAD=host 绑定条目防串换),密钥由 worker 首次启动
 * 自动生成存 <dataDir>/keys/git-credential.key——不要求用户记忆主密码,换机/删 key
 * 后旧密文即失效,重新输入即可。
 *
 * <p>明文账号密码仅存在于 worker 进程内存;磁盘上只有密文。文件按 workspace 隔离
 * (各工作区各自的 .everyagent/.git-credentials.enc),同一工作区内按 host 索引多账号。
 */
@Component
public class GitCredentialStore {

    private static final Logger log = LoggerFactory.getLogger(GitCredentialStore.class);
    private static final String FILE_NAME = ".git-credentials.enc";
    private static final int GCM_TAG_BITS = 128;

    private final WorkerProperties props;
    /** 自动生成的 AES-256 密钥(进程生命周期内常驻,懒加载于首用)。 */
    private volatile SecretKey key;

    public GitCredentialStore(WorkerProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        // 密钥懒加载在 save/load 时触发;此处仅确保 data/keys 目录可建,避免首用才报错。
        try {
            Files.createDirectories(keyPath().getParent());
        } catch (java.io.IOException e) {
            log.warn("git 凭证密钥目录创建失败: {}", e.getMessage());
        }
    }

    // ---- 对外 API ----

    /** 保存(或覆盖)某 host 的凭证,加密落盘工作区。 */
    public synchronized void save(Path workspaceRoot, String host, String username, String password) {
        try {
            byte[] keyBytes = loadKey().getEncoded();
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
            byte[] keyBytes = loadKey().getEncoded();
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

    private Path keyPath() {
        return props.resolveDataDir().resolve("keys").resolve("git-credential.key");
    }

    Path fileOf(Path workspaceRoot) {
        return workspaceRoot.resolve(".everyagent").resolve(FILE_NAME);
    }

    private ObjectNode readJson(Path file) throws java.io.IOException {
        JsonNode node = Json.parse(Files.readString(file));
        return node.isObject() ? (ObjectNode) node : Json.obj();
    }

    private void atomicWrite(Path file, String content) throws java.io.IOException {
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        AtomicFiles.replace(tmp, file); // 原子替换(失败已清理 tmp 后抛出,不残留垃圾)
    }

    /** 自动生成/加载 AES-256 密钥(Base64 落盘 <dataDir>/keys/git-credential.key)。 */
    private synchronized SecretKey loadKey() throws Exception {
        SecretKey cached = key;
        if (cached != null) {
            return cached;
        }
        Path path = keyPath();
        if (Files.isRegularFile(path)) {
            byte[] decoded = Base64.getDecoder().decode(Files.readString(path).trim());
            key = new SecretKeySpec(decoded, "AES");
        } else {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256);
            SecretKey generated = kg.generateKey();
            Files.createDirectories(path.getParent());
            Files.writeString(path, Base64.getEncoder().encodeToString(generated.getEncoded()),
                    StandardCharsets.UTF_8);
            key = generated;
            log.info("已自动生成 git 凭证加密密钥: {}", path);
        }
        return key;
    }
}
