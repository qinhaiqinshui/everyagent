package dev.everyagent.plugin.sandbox.codex.setup;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import dev.everyagent.plugin.sandbox.codex.accounts.SandboxSecrets;
import dev.everyagent.plugin.sandbox.codex.win.Advapi32Ex;
import dev.everyagent.plugin.sandbox.codex.win.Kernel32Ex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * setup_marker.json 两阶段提交（对应 codex sandbox_users.rs::prepare_setup_marker/
 * commit_setup_marker + setup.rs::SetupMarker；分析文档 §5.2.4，不变量①）。
 *
 * <p>阶段一 {@link #prepare}：删旧 marker → SDDL {@code D:P(A;;GA;;;SY)(A;;GA;;;BA)
 * (A;;GA;;;&lt;owner&gt;)} 保护 → CreateFileW(GENERIC_WRITE, share=0, CREATE_NEW)
 * 建空 sentinel——空文件使 readiness 恒失败，天然充当「setup 未完成」哨兵。
 * 阶段二 {@link #commit}：全部步骤成功后重开写 JSON 内容（RFC3339 时间戳）。
 *
 * <p>readiness（对齐 identity.rs::sandbox_setup_is_complete）：marker 存在 ∧ 版本匹配
 * ∧ 凭据文件存在——「marker + 凭据」双闸门。
 */
public final class SetupMarker {

    /** marker 文件名。 */
    public static final String FILE_NAME = "setup_marker.json";

    /** CreateFileW：GENERIC_WRITE。 */
    private static final int GENERIC_WRITE = 0x40000000;
    /** CreateFileW：dwShareMode=0（独占）。 */
    private static final int SHARE_NONE = 0;
    /** CreateFileW：FILE_ATTRIBUTE_NORMAL。 */
    private static final int FILE_ATTRIBUTE_NORMAL = 0x00000080;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SetupMarker() {
    }

    /** marker 读端模型（字段名对齐 codex SetupMarker；read/write_roots 恒空占位）。 */
    public static final class Model {
        @JsonProperty("version")
        public int version;
        @JsonProperty("offline_username")
        public String offlineUsername;
        @JsonProperty("online_username")
        public String onlineUsername;
        @JsonProperty("created_at")
        public String createdAt;
        @JsonProperty("proxy_ports")
        public List<Integer> proxyPorts = new ArrayList<>();
        @JsonProperty("allow_local_binding")
        public boolean allowLocalBinding;
        @JsonProperty("read_roots")
        public List<String> readRoots = new ArrayList<>();
        @JsonProperty("write_roots")
        public List<String> writeRoots = new ArrayList<>();
    }

    /** marker 路径：{@code <codexHome>/.sandbox/setup_marker.json}。 */
    public static Path markerFile(Path codexHome) {
        return codexHome.resolve(".sandbox").resolve(FILE_NAME);
    }

    /** readiness 判定：marker 版本匹配 ∧ 凭据文件存在（双闸门）。 */
    public static boolean isComplete(Path codexHome, int expectedVersion) {
        Model marker = read(codexHome);
        return marker != null && marker.version == expectedVersion
                && marker.offlineUsername != null && marker.onlineUsername != null
                && SandboxSecrets.exists(codexHome);
    }

    /** 读 marker（缺失/损坏/空 sentinel 返回 null——空文件即未完成哨兵）。 */
    public static Model read(Path codexHome) {
        Path file = markerFile(codexHome);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), Model.class);
        } catch (IOException emptyOrCorrupt) {
            return null;
        }
    }

    /**
     * 阶段一：建受保护空 sentinel（对齐 prepare_setup_marker；Windows 专属）。
     * Legacy/Full 语义：句柄即关（commit 时按路径重开），失败抛 SetupException
     * （HELPER_SETUP_MARKER_WRITE_FAILED）。
     */
    public static void prepare(Path codexHome, String realUser) {
        Path markerPath = markerFile(codexHome);
        try {
            Files.deleteIfExists(markerPath);
        } catch (IOException e) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_SETUP_MARKER_WRITE_FAILED,
                    "remove old setup marker " + markerPath + " failed: " + e.getMessage());
        }
        String ownerSid;
        try {
            ownerSid = Advapi32Util.getAccountByName(realUser).sidString;
        } catch (RuntimeException e) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_SETUP_MARKER_WRITE_FAILED,
                    "resolve real user SID for setup marker failed: " + e.getMessage());
        }
        String sddl = "D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GA;;;" + ownerSid + ")";
        PointerByReference sdRef = new PointerByReference();
        if (!Advapi32Ex.INSTANCE.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                sddl, Advapi32Ex.SDDL_REVISION_1, sdRef, new IntByReference())) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_SETUP_MARKER_WRITE_FAILED,
                    "create setup marker security descriptor failed: "
                            + Native.getLastError());
        }
        Pointer sd = sdRef.getValue();
        WinBase.SECURITY_ATTRIBUTES sa = new WinBase.SECURITY_ATTRIBUTES();
        sa.dwLength = new com.sun.jna.platform.win32.WinDef.DWORD(sa.size());
        sa.lpSecurityDescriptor = sd;
        sa.bInheritHandle = false;
        sa.write();
        WinNT.HANDLE handle = Kernel32Ex.INSTANCE.CreateFile(markerPath.toString(),
                GENERIC_WRITE, SHARE_NONE, sa, WinNT.CREATE_NEW, FILE_ATTRIBUTE_NORMAL,
                null);
        int createError = Native.getLastError();
        Kernel32.INSTANCE.LocalFree(sd);
        if (handle == null || handle.equals(WinBase.INVALID_HANDLE_VALUE)) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_SETUP_MARKER_WRITE_FAILED,
                    "create protected setup marker file " + markerPath
                            + " failed: " + createError);
        }
        // Full/Interactive 语义：drop 句柄，commit 时按路径重开（Reopen 形态）。
        Kernel32.INSTANCE.CloseHandle(handle);
    }

    /** 阶段二：提交 marker 内容（对齐 commit_setup_marker；原子替换）。 */
    public static void commit(Path codexHome, int version, String offlineUser, String onlineUser,
            List<Integer> proxyPorts, boolean allowLocalBinding) throws IOException {
        Model marker = new Model();
        marker.version = version;
        marker.offlineUsername = offlineUser;
        marker.onlineUsername = onlineUser;
        marker.createdAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        marker.proxyPorts = new ArrayList<>(proxyPorts);
        marker.allowLocalBinding = allowLocalBinding;
        Path file = markerFile(codexHome);
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        Files.createDirectories(file.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), marker);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicUnsupported) {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), marker);
            Files.deleteIfExists(tmp);
        }
    }
}
