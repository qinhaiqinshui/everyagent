package dev.everyagent.plugin.sandbox.codex.win;

/**
 * 错误码表（设计文档 §2.1 WinErr：NERR_* 与 FWP_E_* 及 APPMODEL 等）。
 *
 * <p>对应 codex identity.rs（凭据失配分类）、runner_client.rs（1056 重试/1223 拒绝）、
 * setup.rs（UAC 取消）、firewall.rs/wfp.rs（FWP_E_* 容错表）。
 * 本步骤只收常量；「凭据失配 / 可刷新 / GPO 覆盖」的分类方法是后续步骤的
 * SandboxIdentity/错误分类器职责。
 *
 * <p>约定：Win32 错误经 GetLastError（或函数返回值）；NERR_* 是 NET_API_STATUS，
 * 不走 GetLastError；FWP_E_* 是 HRESULT，Fwpuclnt 函数直接返回。
 */
public final class WinErr {

    private WinErr() {
    }

    // ---- Win32（WinError.h） ----

    /** WaitForSingleObject 超时返回码（看门狗/握手超时）。 */
    public static final int WAIT_TIMEOUT = 0x00000102;
    /** 文件/目录不存在（卸载路径 NotFound 视作已清）。 */
    public static final int ERROR_FILE_NOT_FOUND = 2;
    /** CreateFile(CREATE_NEW) 语义下的 marker sentinel 已存在（两阶段提交判定）。 */
    public static final int ERROR_FILE_EXISTS = 80;
    /** JNA 载荷未 write 即传 Pointer 时的典型症状（mic 教训）。 */
    public static final int ERROR_NOACCESS = 998;
    /** CreateNamedPipe 客户端被拒（句柄校验/重试路径）。 */
    public static final int ERROR_CALL_NOT_IMPLEMENTED = 120;
    /** CreateProcessWithLogonW：取消阻塞 I/O 时操作恰已完成（再收割一次）。 */
    public static final int ERROR_NOT_FOUND = 1168;
    /** ShellExecuteExW "runas"：用户点了「否」（单列「用户拒绝」，不当作失败）。 */
    public static final int ERROR_CANCELLED = 1223;
    /** CreateNamedPipe：全部实例忙（WaitNamedPipe 轮询）。 */
    public static final int ERROR_PIPE_BUSY = 231;
    /** ConnectNamedPipe：客户端已抢先连上（视为连接成功）。 */
    public static final int ERROR_PIPE_CONNECTED = 535;
    /** NetLocalGroupAdd 等价的 Win32 别名已存在码（与 NERR_GroupExists 双容忍）。 */
    public static final int ERROR_ALIAS_EXISTS = 1379;
    /** CreateProcessWithLogonW：Secondary Logon 服务已在处理中（原凭据重试一次）。 */
    public static final int ERROR_SERVICE_ALREADY_RUNNING = 1056;
    /** CreateProcessWithLogonW：登录会话不存在（凭据/会话失效类）。 */
    public static final int ERROR_NO_SUCH_LOGON_SESSION = 1312;
    /** 用户名或密码失配（凭据失配 → SandboxAccountCredentialMismatch）。 */
    public static final int ERROR_LOGON_FAILURE = 1326;
    /** 密码已过期且须先改（readiness 识别的中间态）。 */
    public static final int ERROR_PASSWORD_MUST_CHANGE = 1907;
    /** 密码已过期（凭据失配类）。 */
    public static final int ERROR_PASSWORD_EXPIRED = 1330;
    /** 账户被禁用（修复路径的中间态：就绪前先禁用）。 */
    public static final int ERROR_ACCOUNT_DISABLED = 1331;
    /** 组成员不存在（AddMembers 失败的可分类原因）。 */
    public static final int ERROR_NO_SUCH_MEMBER = 1387;
    /** 账户不存在（被 net user del 删除——LookupAccountName/LogonUser 均可能回此码）。 */
    public static final int ERROR_NO_SUCH_USER = 1317;
    /** 帐户名与 SID 间无映射（账户/组被删后 LookupAccountName 的典型失败码——自愈矩阵 §4.3.1 #2）。 */
    public static final int ERROR_NONE_MAPPED = 1332;

    // ---- HRESULT ----

    /** CoInitializeEx：线程已是别的 apartment 模式（防火墙 COM 容忍，继续用现有）。 */
    public static final int RPC_E_CHANGED_MODE = 0x80010106;
    /** GetPackageFullName：进程无 MSIX 包身份（Legacy 形态判定）。 */
    public static final int APPMODEL_ERROR_NO_PACKAGE = 15700;

    // ---- NET_API_STATUS（lmerr.h；不走 GetLastError） ----

    public static final int NERR_Success = 0;
    /** 用户名参数无效（如超长：Windows SAM 用户名上限 20 字符）。 */
    public static final int NERR_BadUsername = 2202;
    /** 用户不存在。 */
    public static final int NERR_UserNotFound = 2221;
    /** 用户已存在（NetUserAdd 幂等判定 → 转 NetUserSetInfo）。 */
    public static final int NERR_UserExists = 2224;
    /** 组已存在（ensure_sandbox_users_group 视为成功）。 */
    public static final int NERR_GroupExists = 2223;
    /** 组不存在（卸载 NetLocalGroupDel 容忍）。 */
    public static final int NERR_GroupNotFound = 2230;
    /** 组成员不存在（成员操作容忍/分类）。 */
    public static final int NERR_MemberNotFound = 2238;
    /** 密码短于策略（建户回退改密路径的诱因之一）。 */
    public static final int NERR_PasswordTooShort = 2233;

    // ---- FWP_E_*（fwpmu.h；FWPM_E_BASE=0x80320001 起） ----

    /** FWP 错误基值。 */
    public static final int FWPM_E_BASE = 0x80320001;
    /** 引擎中对象不存在（删除幂等容忍）。 */
    public static final int FWP_E_NOT_FOUND = 0x80320006;
    /** 对象已存在（ensure_provider/ensure_sublayer 容忍）。 */
    public static final int FWP_E_ALREADY_EXISTS = 0x80320007;
    /** 引擎会话等待超时。 */
    public static final int FWP_E_TIMEOUT = 0x80320009;
    /** 对象正被引用（事务冲突/清理重试）。 */
    public static final int FWP_E_IN_USE = 0x8032000B;
    /** 无事务在途（Abort 兜底时可能遇到）。 */
    public static final int FWP_E_NO_TXN_IN_PROGRESS = 0x8032000E;
    /** 已有事务在途（并发安装的互斥症状）。 */
    public static final int FWP_E_TXN_IN_PROGRESS = 0x8032000F;
    /** 事务已中止（Commit 失败即回滚的确认）。 */
    public static final int FWP_E_TXN_ABORTED = 0x80320010;
    /** 会话已中止（引擎侧断连，全量重试）。 */
    public static final int FWP_E_SESSION_ABORTED = 0x80320011;
    /** filter 形状越界（NAME_RESOLUTION_CACHE 层静态 filter 会触发，故 codex 有意省略）。 */
    public static final int FWP_E_OUT_OF_BOUNDS = 0x80320020;
    /** filter 不存在（delete_filter_if_present 容忍）。 */
    public static final int FWP_E_FILTER_NOT_FOUND = 0x80320031;
}
