package dev.everyagent.plugin.sandbox.codex.accounts;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinCrypt;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;

import dev.everyagent.plugin.sandbox.codex.win.Advapi32Ex;
import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs.EXPLICIT_ACCESS_W;
import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs.TRUSTEE_W;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * DPAPI 机器作用域凭据存储（对应 codex dpapi.rs + sandbox_users.rs::write_secrets，
 * 设计文档 §2.3 DpapiSecrets）。
 *
 * <p>两账户密码经 {@code CryptProtectData(CRYPTPROTECT_UI_FORBIDDEN|
 * CRYPTPROTECT_LOCAL_MACHINE)} 加密后 base64，写入
 * {@code <codexHome>/.sandbox-secrets/sandbox_users.json}——机器作用域让提权 helper
 * 加密、非提权 runner 解密。目录用 DENY ACE 封死沙箱组访问（{@link #denyGroupFullAccess}）。
 */
public final class SandboxSecrets {

    /** 凭据文件名（对齐 sandbox_users.json）。 */
    public static final String FILE_NAME = "sandbox_users.json";

    /** DPAPI flags：禁 UI + 机器作用域（对齐 dpapi.rs protect/unprotect）。 */
    private static final int DPAPI_FLAGS =
            WinCrypt.CRYPTPROTECT_UI_FORBIDDEN | WinCrypt.CRYPTPROTECT_LOCAL_MACHINE;

    /** FILE_ALL_ACCESS（DENY 全掩码：读/写/执行/删除/读控制/写 DACL/写 owner/同步）。 */
    public static final int FILE_ALL_ACCESS = 0x001F01FF;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SandboxSecrets() {
    }

    /** 凭据文件路径。 */
    public static Path secretsFile(Path codexHome) {
        return codexHome.resolve(".sandbox-secrets").resolve(FILE_NAME);
    }

    /** 凭据记录（JSON 形态对齐 SandboxUserRecord：username + base64(DPAPI blob)）。 */
    public static final class UserRecord {
        @JsonProperty("username")
        public String username;
        @JsonProperty("password")
        public String password;

        public UserRecord() {
        }

        public UserRecord(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    /** sandbox_users.json 模型（version + offline/online，对齐 SandboxUsersFile）。 */
    public static final class SandboxUsersFile {
        @JsonProperty("version")
        public int version;
        @JsonProperty("offline")
        public UserRecord offline;
        @JsonProperty("online")
        public UserRecord online;
    }

    /** DPAPI 加密（机器作用域；Windows 专属）。 */
    public static byte[] protect(byte[] data) {
        return Crypt32Util.cryptProtectData(data, DPAPI_FLAGS);
    }

    /** DPAPI 解密（机器作用域；Windows 专属）。 */
    public static byte[] unprotect(byte[] blob) {
        return Crypt32Util.cryptUnprotectData(blob, DPAPI_FLAGS);
    }

    /** 写凭据文件（对齐 write_secrets：pretty JSON，目录不存在则建）。 */
    public static void write(Path codexHome, int version, String offlineUser, String offlinePwd,
            String onlineUser, String onlinePwd) throws IOException {
        SandboxUsersFile file = new SandboxUsersFile();
        file.version = version;
        file.offline = new UserRecord(offlineUser,
                Base64.getEncoder().encodeToString(protect(offlinePwd.getBytes(StandardCharsets.UTF_8))));
        file.online = new UserRecord(onlineUser,
                Base64.getEncoder().encodeToString(protect(onlinePwd.getBytes(StandardCharsets.UTF_8))));
        Path path = secretsFile(codexHome);
        Files.createDirectories(path.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), file);
    }

    /**
     * 读取指定账户的明文密码（对齐 identity.rs::decode_password：
     * 读 JSON → base64 解码 → CryptUnprotectData → UTF-8）。
     *
     * @throws CredentialsFileException 凭据文件态失真（缺失/损坏/版本失配/无该账户记录/
     *         DPAPI 解密失败）——执行链据此触发强制重 setup 重写凭据（design.md §4.3.1 #3）
     * @throws IOException 其他 IO 失败
     */
    public static String readPassword(Path codexHome, int expectedVersion, String username)
            throws IOException {
        Path path = secretsFile(codexHome);
        if (!Files.exists(path)) {
            throw new CredentialsFileException("sandbox users file missing: " + path);
        }
        SandboxUsersFile file;
        try {
            file = MAPPER.readValue(path.toFile(), SandboxUsersFile.class);
        } catch (IOException corrupt) {
            throw new CredentialsFileException("sandbox users file corrupt: " + path
                    + " (" + corrupt.getMessage() + ")");
        }
        if (file.version != expectedVersion) {
            throw new CredentialsFileException("sandbox users file version mismatch: expected "
                    + expectedVersion + ", got " + file.version);
        }
        UserRecord record = username.equals(file.offline.username) ? file.offline
                : username.equals(file.online.username) ? file.online : null;
        if (record == null) {
            throw new CredentialsFileException(
                    "sandbox users file has no credentials for " + username);
        }
        try {
            return new String(unprotect(Base64.getDecoder().decode(record.password)),
                    StandardCharsets.UTF_8);
        } catch (RuntimeException decryptOrEncodingFailed) {
            // DPAPI 解密失败 = blob 与当前机器/账户态失配（文件被篡改/换机拷贝）——同属可自愈
            throw new CredentialsFileException("sandbox credentials undecryptable for "
                    + username + ": " + decryptOrEncodingFailed.getMessage());
        }
    }

    /**
     * 凭据文件态失真（{@link #readPassword} 专属失败）：文件缺失/损坏/版本失配/记录缺失/
     * DPAPI 解密失败——重跑 setup 重写凭据文件即愈。
     */
    public static final class CredentialsFileException extends IOException {
        public CredentialsFileException(String message) {
            super(message);
        }
    }

    /** 凭据文件是否存在（readiness「marker + 凭据」双闸门之一）。 */
    public static boolean exists(Path codexHome) {
        return Files.exists(secretsFile(codexHome));
    }

    /**
     * 给目录挂沙箱组 DENY 全掩码 ACE（对齐 lock_sandbox_dir 的 secrets 分支语义：
     * 组 DENY、OI|CI 继承；本域目录锁定，不属 acl/ 授权域）。
     * Windows 专属；返回 false 表示 Win32 失败（fail-closed 由调用方决定）。
     */
    public static boolean denyGroupFullAccess(Path dir, String groupSidString) {
        WinNT.PSIDByReference psidRef = new WinNT.PSIDByReference();
        if (!Advapi32.INSTANCE.ConvertStringSidToSid(groupSidString, psidRef)) {
            return false;
        }
        Pointer psid = psidRef.getValue().getPointer();
        EXPLICIT_ACCESS_W ea = new EXPLICIT_ACCESS_W();
        ea.grfAccessPermissions = FILE_ALL_ACCESS;
        ea.grfAccessMode = Advapi32Ex.DENY_ACCESS;
        ea.grfInheritance = Advapi32Ex.OBJECT_INHERIT_ACE | Advapi32Ex.CONTAINER_INHERIT_ACE;
        TRUSTEE_W trustee = ea.Trustee;
        trustee.pMultipleTrustee = null;
        trustee.MultipleTrusteeOperation = 0;
        trustee.TrusteeForm = 0; // TRUSTEE_IS_SID
        trustee.TrusteeType = 2; // TRUSTEE_IS_GROUP
        trustee.ptstrName = psid;
        ea.write();

        PointerByReference newAcl = new PointerByReference();
        int set = Advapi32Ex.INSTANCE.SetEntriesInAclW(1, new EXPLICIT_ACCESS_W[] { ea },
                null, newAcl);
        if (set != 0) {
            Kernel32.INSTANCE.LocalFree(psid);
            return false;
        }
        try {
            int result = Advapi32.INSTANCE.SetNamedSecurityInfo(dir.toString(),
                    1 /* SE_FILE_OBJECT */, Advapi32Ex.DACL_SECURITY_INFORMATION,
                    null, null, newAcl.getValue(), null);
            return result == 0;
        } finally {
            Kernel32.INSTANCE.LocalFree(newAcl.getValue());
            Kernel32.INSTANCE.LocalFree(psid);
        }
    }
}
