package dev.everyagent.plugin.git;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.util.AtomicFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GitCredentialStore 主密钥存取层测试(安全整改 M-11;条目级 AES-GCM 语义经由
 * roundtrip/迁移用例间接锁定)。全部用 @TempDir 隔离,不触碰真实 ~/.everyagent 与真实工作区:
 * ① save/load roundtrip(Windows 本机直接覆盖 DPAPI 路径,其余环境覆盖外置回退);
 * ② 旧布局(明文 key + 旧密文)自动迁移,旧密文仍可解;
 * ③ 垃圾/损坏 key 文件容错自愈。
 */
class GitCredentialStoreTest {

    // ---- 测试脚手架 ----

    private static byte[] keyOf(String seed) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 写旧版布局的明文主密钥文件(整文件裸 base64,与密文同目录)。 */
    private static Path writeLegacyKey(Path workspaceRoot, byte[] keyBytes) throws Exception {
        Path legacy = workspaceRoot.resolve(".everyagent").resolve(".git-credential.key");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, Base64.getEncoder().encodeToString(keyBytes));
        return legacy;
    }

    /** 按旧版条目格式(AES-GCM、随机 IV、AAD=host、密文 JSON 形态)手工构造密文——等价旧版本产物。 */
    private static void writeLegacyCiphertext(Path workspaceRoot, byte[] keyBytes, String host,
            String username, String password) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(host.getBytes(StandardCharsets.UTF_8));
        byte[] enc = cipher.doFinal((username + "\u0000" + password).getBytes(StandardCharsets.UTF_8));
        ObjectNode root = Json.obj().put("version", 1);
        root.putObject("entries").set(host, Json.obj()
                .put("iv", Base64.getEncoder().encodeToString(iv))
                .put("cipher", Base64.getEncoder().encodeToString(enc))
                .put("ts", System.currentTimeMillis()));
        Path file = workspaceRoot.resolve(".everyagent").resolve(".git-credentials.enc");
        Files.createDirectories(file.getParent());
        AtomicFiles.writeText(file, Json.write(root));
    }

    // ---- ① roundtrip ----

    @Test
    void saveLoadRoundtrip(@TempDir Path workspace, @TempDir Path home) throws Exception {
        GitCredentialStore store = new GitCredentialStore(home, false);
        assertFalse(store.has(workspace, "github.com"));
        store.save(workspace, "github.com", "alice", "s3cret-P@ss");
        assertTrue(store.has(workspace, "github.com"));

        GitCredentialStore.UsernamePassword got = store.load(workspace, "github.com").orElseThrow();
        assertEquals("alice", got.username());
        assertEquals("s3cret-P@ss", got.password());
        assertTrue(store.load(workspace, "other.host").isEmpty(), "未存 host 返回 empty");
        assertTrue(Files.isRegularFile(workspace.resolve(".everyagent/.git-credentials.enc")),
                "密文文件位置不变(仍在工作区)");

        if (GitCredentialStore.dpapiAvailable()) {
            // Windows:主密钥 = DPAPI 当前用户包裹信封,仍在工作区该路径,但绝非裸 base64 明文
            Path keyFile = workspace.resolve(".everyagent/.git-credential.key");
            assertTrue(Files.isRegularFile(keyFile));
            String content = Files.readString(keyFile);
            assertThrows(Exception.class, () -> Base64.getDecoder().decode(content),
                    "工作区密钥文件不得是整文件裸 base64 明文");
            JsonNode envelope = Json.parse(content);
            assertEquals(2, envelope.path("v").asInt());
            assertEquals("dpapi-user", envelope.path("k").asString());
            assertTrue(envelope.path("d").asString().length() > 60,
                    "DPAPI blob 应显著长于 32B 密钥的 base64");
        } else {
            // 回退:主密钥外置 home,工作区内不出现任何密钥文件
            assertFalse(Files.exists(workspace.resolve(".everyagent/.git-credential.key")));
            assertTrue(Files.isRegularFile(store.outsideKeyFile(workspace)));
        }
        // "重启"语义:新实例从盘上密钥形态恢复仍可解
        assertEquals("alice",
                new GitCredentialStore(home, false).load(workspace, "github.com").orElseThrow().username());
    }

    // ---- ② 旧布局 → 新布局迁移 ----

    @Test
    void migratesLegacyPlaintextKeyAndOldCiphertextStillDecrypts(@TempDir Path workspace, @TempDir Path home)
            throws Exception {
        byte[] legacyKey = keyOf("legacy-key-material");
        Path legacy = writeLegacyKey(workspace, legacyKey);
        writeLegacyCiphertext(workspace, legacyKey, "github.com", "bob", "hunter2");

        GitCredentialStore store = new GitCredentialStore(home, false);
        GitCredentialStore.UsernamePassword got = store.load(workspace, "github.com").orElseThrow(); // 触发迁移
        assertEquals("bob", got.username());
        assertEquals("hunter2", got.password());

        if (GitCredentialStore.dpapiAvailable()) {
            // Windows:同路径文件已变为 DPAPI 信封,不再含明文主密钥
            String content = Files.readString(legacy);
            JsonNode envelope = Json.parse(content);
            assertEquals(2, envelope.path("v").asInt());
            assertEquals("dpapi-user", envelope.path("k").asString());
            assertFalse(content.contains(Base64.getEncoder().encodeToString(legacyKey)),
                    "迁移后工作区密钥文件不得含明文主密钥");
        } else {
            // 回退:工作区内明文 key 文件被删除,主密钥外置且值不变
            assertFalse(Files.exists(legacy), "迁移后工作区不得残留主密钥文件");
            JsonNode envelope = Json.parse(Files.readString(store.outsideKeyFile(workspace)));
            assertEquals("raw", envelope.path("k").asString());
            assertEquals(Base64.getEncoder().encodeToString(legacyKey), envelope.path("d").asString(),
                    "主密钥值不变,仅换存放形态");
        }
        // 幂等 + "重启"后从新形态读取仍解得开旧密文(条目未动)
        assertEquals("hunter2", store.load(workspace, "github.com").orElseThrow().password());
        assertEquals("hunter2",
                new GitCredentialStore(home, false).load(workspace, "github.com").orElseThrow().password());
        // 迁移不碰条目级逻辑:save 覆盖后照常
        store.save(workspace, "github.com", "bob", "new-pass");
        assertEquals("new-pass", store.load(workspace, "github.com").orElseThrow().password());
    }

    @Test
    void migratesLegacyKeyEvenWithoutCiphertext(@TempDir Path workspace, @TempDir Path home) throws Exception {
        byte[] legacyKey = keyOf("no-ciphertext-key");
        Path legacy = writeLegacyKey(workspace, legacyKey);

        GitCredentialStore store = new GitCredentialStore(home, false);
        assertTrue(store.load(workspace, "github.com").isEmpty(), "无密文文件时 load 不触发密钥迁移");
        store.save(workspace, "github.com", "u1", "p1"); // save 触发迁移(无密文可验证 → 放行)
        assertEquals("p1", store.load(workspace, "github.com").orElseThrow().password());

        if (GitCredentialStore.dpapiAvailable()) {
            assertEquals("dpapi-user", Json.parse(Files.readString(legacy)).path("k").asString());
        } else {
            assertFalse(Files.exists(legacy), "迁移后工作区不得残留主密钥文件");
        }
    }

    @Test
    void forcedFallbackMigratesKeyOutsideWorkspace(@TempDir Path workspace, @TempDir Path home,
            @TempDir Path otherWorkspace) throws Exception {
        byte[] legacyKey = keyOf("fallback-key");
        Path legacy = writeLegacyKey(workspace, legacyKey);
        writeLegacyCiphertext(workspace, legacyKey, "gitlab.com", "dave", "fb-pass");

        GitCredentialStore store = new GitCredentialStore(home, true); // 禁 DPAPI → 强制外置回退
        GitCredentialStore.UsernamePassword got = store.load(workspace, "gitlab.com").orElseThrow();
        assertEquals("dave", got.username());
        assertEquals("fb-pass", got.password());

        assertFalse(Files.exists(legacy), "回退迁移后工作区内不得残留任何主密钥文件");
        Path outside = store.outsideKeyFile(workspace);
        assertTrue(Files.isRegularFile(outside));
        assertTrue(outside.startsWith(home), "主密钥必须落在工作区外(home 注入目录)");
        JsonNode envelope = Json.parse(Files.readString(outside));
        assertEquals(2, envelope.path("v").asInt());
        assertEquals("raw", envelope.path("k").asString());
        assertEquals(Base64.getEncoder().encodeToString(legacyKey), envelope.path("d").asString(),
                "主密钥值不变,仅换存放位置");

        // 工作区隔离:另一工作区有各自的密钥文件
        store.save(otherWorkspace, "github.com", "x", "y");
        Path outside2 = store.outsideKeyFile(otherWorkspace);
        assertTrue(Files.isRegularFile(outside2));
        assertFalse(outside.equals(outside2));
        assertEquals("y", store.load(otherWorkspace, "github.com").orElseThrow().password());
    }

    @Test
    void migrationVerificationFailureKeepsLegacyFile(@TempDir Path workspace, @TempDir Path home)
            throws Exception {
        byte[] wrongKey = keyOf("wrong-key"); // 与密文所用密钥不同
        Path legacy = writeLegacyKey(workspace, wrongKey);
        writeLegacyCiphertext(workspace, keyOf("real-key"), "github.com", "eve", "secret");

        GitCredentialStore store = new GitCredentialStore(home, true);
        assertTrue(store.load(workspace, "github.com").isEmpty(), "旧密文与 key 失配 → 按无凭证处理");
        assertTrue(Files.exists(legacy), "迁移验证失败不得删除旧文件");

        // 二次运行收敛:新形态成为权威,旧明文残留被清理,行为稳定不抛
        GitCredentialStore again = new GitCredentialStore(home, true);
        assertTrue(again.load(workspace, "github.com").isEmpty());
        assertFalse(Files.exists(legacy), "旧明文残留最终被收敛清理");
    }

    // ---- ③ 垃圾/损坏 key 文件容错 ----

    @Test
    void corruptKeyFileSelfHealsWithoutThrowing(@TempDir Path workspace, @TempDir Path home) throws Exception {
        boolean dpapi = GitCredentialStore.dpapiAvailable();
        GitCredentialStore store = new GitCredentialStore(home, false);
        store.save(workspace, "github.com", "warm", "up"); // 建立正常态,密钥落位
        Path keyFile = dpapi
                ? workspace.resolve(".everyagent/.git-credential.key")
                : store.outsideKeyFile(workspace);
        assertTrue(Files.isRegularFile(keyFile));

        // 垃圾 1:非法 base64
        Files.writeString(keyFile, "!!!this-is-not-base64!!!");
        GitCredentialStore s1 = new GitCredentialStore(home, false);
        assertTrue(s1.load(workspace, "github.com").isEmpty(), "损坏 key 下 load 容错为 empty");
        s1.save(workspace, "github.com", "carol", "pw-1"); // 自愈重生成(覆盖损坏文件)
        assertEquals("pw-1", s1.load(workspace, "github.com").orElseThrow().password());

        // 垃圾 2:合法 base64 但非 AES 密钥长度
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(new byte[3]));
        GitCredentialStore s2 = new GitCredentialStore(home, false);
        assertTrue(s2.load(workspace, "github.com").isEmpty());
        s2.save(workspace, "github.com", "carol", "pw-2");
        assertEquals("pw-2", s2.load(workspace, "github.com").orElseThrow().password());

        // 垃圾 3:伪造信封(payload 解不开/长度非法)
        Files.writeString(keyFile, "{\"v\":2,\"k\":\"" + (dpapi ? "dpapi-user" : "raw")
                + "\",\"d\":\"" + Base64.getEncoder().encodeToString(new byte[5]) + "\"}");
        GitCredentialStore s3 = new GitCredentialStore(home, false);
        assertTrue(s3.load(workspace, "github.com").isEmpty());
        s3.save(workspace, "github.com", "carol", "pw-3");
        assertEquals("pw-3", s3.load(workspace, "github.com").orElseThrow().password());
    }
}
