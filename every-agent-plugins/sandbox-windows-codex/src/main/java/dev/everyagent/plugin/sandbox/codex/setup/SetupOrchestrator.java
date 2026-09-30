package dev.everyagent.plugin.sandbox.codex.setup;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI.SHELLEXECUTEINFO;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import dev.everyagent.plugin.sandbox.codex.win.Advapi32Ex;
import dev.everyagent.plugin.sandbox.codex.win.WinErr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * worker 侧 setup 编排（对应 codex setup.rs 编排层；分析文档 §5.1，设计文档 §2.4）。
 *
 * <p>编排层永不碰特权 API（分析文档 §5 分层原则）：组装 payload（base64）→
 * readiness 短路（marker + 凭据双闸门）→ 进程内 singleflight（ConcurrentHashMap 按
 * payload 编码去重，等价请求共享同一结果与异常）→ 已提权则进程内直调
 * {@link SetupHelperMain#executePayload}，未提权则 ShellExecuteExW "runas" 提权拉起
 * 同 jar 的 helper（当前 java.exe -cp；SEE_MASK_NOCLOSEPROCESS|SEE_MASK_NOASYNC +
 * SW_HIDE，ERROR_CANCELLED=1223 单列「用户拒绝」）→ 等待退出码，非 0 读
 * setup_error.json 还原精确错误，退出码 0 再校验 marker（防 helper 假成功）。
 *
 * <p>payload 超 argv 阈值（24,000 UTF-16 单位）落 {@code .sandbox/setup_payload.json}
 * 传 {@code --setup-payload-file}（环境分块暂缓——设计文档 §1.2）。
 */
public final class SetupOrchestrator {

    /** SEE_MASK_NOCLOSEPROCESS。 */
    private static final int SEE_MASK_NOCLOSEPROCESS = 0x00000040;
    /** SEE_MASK_NOASYNC（无消息循环线程必须同步激活）。 */
    private static final int SEE_MASK_NOASYNC = 0x00000100;
    /** SW_HIDE。 */
    private static final int SW_HIDE = 0;
    /** WaitForSingleObject INFINITE。 */
    private static final int WAIT_INFINITE = -1;

    /** SECURITY_NT_AUTHORITY（{0,0,0,0,0,5}）。 */
    private static final byte[] SECURITY_NT_AUTHORITY = { 0, 0, 0, 0, 0, 5 };
    /** SECURITY_BUILTIN_DOMAIN_RID。 */
    private static final int SECURITY_BUILTIN_DOMAIN_RID = 0x00000020;
    /** DOMAIN_ALIAS_RID_ADMINS。 */
    private static final int DOMAIN_ALIAS_RID_ADMINS = 0x00000220;

    private static final ConcurrentHashMap<String, CompletableFuture<Void>> FLIGHTS =
            new ConcurrentHashMap<>();

    private SetupOrchestrator() {
    }

    /**
     * 确保 setup 完成（幂等；Full/ProvisionOnly/Remove 通用入口——Remove 走同一提权通道）。
     *
     * @throws SetupErrorReport.SetupException 结构化失败（code 可分类重试/引导）
     */
    public static void ensureSetup(SetupPayload payload) {
        Path codexHome = Path.of(payload.model().codexHome);
        if (payload.mode() != SetupPayload.Mode.REMOVE
                && SetupMarker.isComplete(codexHome, SetupPayload.SETUP_VERSION)) {
            return; // marker + 凭据双闸门短路（幂等，对齐 sandbox_setup_is_complete）
        }
        String key = payload.encodeBase64();
        CompletableFuture<Void> flight = new CompletableFuture<>();
        CompletableFuture<Void> existing = FLIGHTS.putIfAbsent(key, flight);
        if (existing != null) {
            try {
                existing.join();
            } catch (CompletionException e) {
                throw asSetupException(e.getCause());
            }
            return;
        }
        try {
            runSetupExe(payload);
            flight.complete(null);
        } catch (Throwable t) {
            flight.completeExceptionally(t);
            throw asSetupException(t);
        } finally {
            FLIGHTS.remove(key, flight);
        }
    }

    /** 单次执行（leader 路径；不提权 spawn → runas 提权 spawn → 假成功校验）。 */
    private static void runSetupExe(SetupPayload payload) {
        Path codexHome = Path.of(payload.model().codexHome);
        try {
            Files.createDirectories(SandboxDirs.sandboxDir(codexHome));
        } catch (IOException e) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.ORCHESTRATOR_SANDBOX_DIR_CREATE_FAILED, e.getMessage());
        }
        boolean clearedReport;
        try {
            SetupErrorReport.clear(codexHome);
            clearedReport = true;
        } catch (IOException e) {
            clearedReport = false; // 清除失败只降级错误还原精度（对齐 codex）
        }
        try {
            if (isElevated()) {
                // 已提权：进程内直调 helper（同 JVM，无第二个进程）。
                try {
                    SetupHelperMain.executePayload(payload);
                } catch (IOException e) {
                    throw new SetupErrorReport.SetupException(
                            SetupErrorReport.HELPER_UNKNOWN_ERROR, e.getMessage(), e);
                }
            } else {
                int exitCode = launchElevatedHelper(payload, codexHome);
                if (exitCode != 0) {
                    throw reportHelperFailure(codexHome, clearedReport, exitCode);
                }
            }
            if (payload.mode() != SetupPayload.Mode.REMOVE
                    && !SetupMarker.isComplete(codexHome, SetupPayload.SETUP_VERSION)) {
                throw new SetupErrorReport.SetupException(
                        SetupErrorReport.ORCHESTRATOR_HELPER_INCOMPLETE,
                        "setup helper exited successfully before setup completed");
            }
            try {
                SetupErrorReport.clear(codexHome);
            } catch (IOException ignored) {
                // 成功后清理失败不阻断（对齐 codex log_note 语义）
            }
        } catch (SetupErrorReport.SetupException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.ORCHESTRATOR_HELPER_LAUNCH_FAILED, e.getMessage(), e);
        }
    }

    /** 非 0 退出码的错误还原（对齐 report_helper_failure：读 setup_error.json）。 */
    private static SetupErrorReport.SetupException reportHelperFailure(Path codexHome,
            boolean clearedReport, int exitCode) {
        String detail = "setup helper exited with status " + exitCode;
        if (!clearedReport) {
            return new SetupErrorReport.SetupException(
                    SetupErrorReport.ORCHESTRATOR_HELPER_EXIT_NONZERO, detail);
        }
        try {
            return SetupErrorReport.read(codexHome)
                    .map(r -> new SetupErrorReport.SetupException(r.code, r.message))
                    .orElseGet(() -> new SetupErrorReport.SetupException(
                            SetupErrorReport.ORCHESTRATOR_HELPER_EXIT_NONZERO, detail));
        } catch (IOException e) {
            return new SetupErrorReport.SetupException(
                    SetupErrorReport.ORCHESTRATOR_HELPER_REPORT_READ_FAILED,
                    detail + "; failed to read setup_error.json: " + e.getMessage());
        }
    }

    /** ShellExecuteExW "runas" 提权拉起 helper（对齐 run_setup_exe_payload 提权分支）。 */
    private static int launchElevatedHelper(SetupPayload payload, Path codexHome) {
        String payloadArg = payload.encodeBase64();
        String argFlag = "--setup-payload";
        if (!SetupPayload.fitsSingleArg(payloadArg)) {
            try {
                Path file = SetupPayload.writePayloadFile(codexHome, payload);
                payloadArg = file.toString();
                argFlag = "--setup-payload-file";
            } catch (IOException e) {
                throw new SetupErrorReport.SetupException(
                        SetupErrorReport.ORCHESTRATOR_PAYLOAD_SERIALIZE_FAILED,
                        "write payload file failed: " + e.getMessage());
            }
        }
        List<String> argv = new ArrayList<>(List.of(javaExecutable(), "-cp",
                System.getProperty("java.class.path"),
                SetupHelperMain.class.getName(), argFlag, payloadArg));
        SHELLEXECUTEINFO sei = new SHELLEXECUTEINFO();
        sei.cbSize = sei.size();
        sei.fMask = SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC;
        sei.lpVerb = "runas";
        sei.lpFile = argv.get(0);
        sei.lpParameters = joinCommandLine(argv.subList(1, argv.size()));
        sei.nShow = SW_HIDE; // 隐藏提权 helper 窗口
        boolean ok = Shell32.INSTANCE.ShellExecuteEx(sei);
        if (!ok || sei.hProcess == null) {
            int lastError = com.sun.jna.Native.getLastError();
            String code = lastError == WinErr.ERROR_CANCELLED
                    ? SetupErrorReport.ORCHESTRATOR_HELPER_LAUNCH_CANCELED
                    : SetupErrorReport.ORCHESTRATOR_HELPER_LAUNCH_FAILED;
            throw new SetupErrorReport.SetupException(code,
                    "ShellExecuteExW failed to launch setup helper: " + lastError);
        }
        Kernel32.INSTANCE.WaitForSingleObject(sei.hProcess, WAIT_INFINITE);
        IntByReference exitCode = new IntByReference(1);
        Kernel32.INSTANCE.GetExitCodeProcess(sei.hProcess, exitCode);
        Kernel32.INSTANCE.CloseHandle(sei.hProcess);
        return exitCode.getValue();
    }

    /**
     * 提权判定（对齐 is_elevated）：AllocateAndInitializeSid(NT Authority,
     * BUILTIN_DOMAIN_RID, DOMAIN_ALIAS_RID_ADMINS) + CheckTokenMembership。
     */
    public static boolean isElevated() {
        Memory authority = new Memory(SECURITY_NT_AUTHORITY.length);
        authority.write(0, SECURITY_NT_AUTHORITY, 0, SECURITY_NT_AUTHORITY.length);
        PointerByReference sidRef = new PointerByReference();
        if (!Advapi32Ex.INSTANCE.AllocateAndInitializeSid(authority, (byte) 2,
                SECURITY_BUILTIN_DOMAIN_RID, DOMAIN_ALIAS_RID_ADMINS, 0, 0, 0, 0, 0, 0,
                sidRef)) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.ORCHESTRATOR_ELEVATION_CHECK_FAILED,
                    "AllocateAndInitializeSid failed: " + com.sun.jna.Native.getLastError());
        }
        Pointer adminsGroup = sidRef.getValue();
        try {
            IntByReference isMember = new IntByReference();
            if (!Advapi32Ex.INSTANCE.CheckTokenMembership(null, adminsGroup, isMember)) {
                throw new SetupErrorReport.SetupException(
                        SetupErrorReport.ORCHESTRATOR_ELEVATION_CHECK_FAILED,
                        "CheckTokenMembership failed: " + com.sun.jna.Native.getLastError());
            }
            return isMember.getValue() != 0;
        } finally {
            Advapi32Ex.INSTANCE.FreeSid(adminsGroup);
        }
    }

    /** 当前 JVM 的 java.exe 绝对路径（helper 同 jar 运行的载体）。 */
    static String javaExecutable() {
        return ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name", "").toLowerCase().contains("win")
                                ? "java.exe" : "java").toString());
    }

    /** 命令行合成（对齐 winutil.rs::argv_to_command_line）。 */
    static String joinCommandLine(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (String arg : args) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(quoteWindowsArg(arg));
        }
        return sb.toString();
    }

    /** 单参数 CRT 引号转义（对齐 winutil.rs::quote_windows_arg / runner 同名实现）。 */
    static String quoteWindowsArg(String arg) {
        boolean needsQuotes = arg.isEmpty()
                || arg.chars().anyMatch(c -> c == ' ' || c == '\t' || c == '\n' || c == '\r'
                        || c == '"');
        if (!needsQuotes) {
            return arg;
        }
        StringBuilder quoted = new StringBuilder(arg.length() + 2).append('"');
        int backslashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char ch = arg.charAt(i);
            if (ch == '\\') {
                backslashes++;
            } else if (ch == '"') {
                quoted.append("\\".repeat(backslashes * 2 + 1)).append('"');
                backslashes = 0;
            } else {
                if (backslashes > 0) {
                    quoted.append("\\".repeat(backslashes));
                    backslashes = 0;
                }
                quoted.append(ch);
            }
        }
        if (backslashes > 0) {
            quoted.append("\\".repeat(backslashes * 2));
        }
        return quoted.append('"').toString();
    }

    private static SetupErrorReport.SetupException asSetupException(Throwable t) {
        if (t instanceof SetupErrorReport.SetupException setup) {
            return setup;
        }
        return new SetupErrorReport.SetupException(SetupErrorReport.HELPER_UNKNOWN_ERROR,
                t.getMessage() == null ? t.toString() : t.getMessage(), t);
    }
}
