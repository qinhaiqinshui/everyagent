package dev.everyagent.plugin.sandbox.codex.win;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import dev.everyagent.plugin.sandbox.codex.win.struct.FwpmTypes.FWPM_FILTER0;
import dev.everyagent.plugin.sandbox.codex.win.struct.FwpmTypes.FWPM_PROVIDER0;
import dev.everyagent.plugin.sandbox.codex.win.struct.FwpmTypes.FWPM_SESSION0;
import dev.everyagent.plugin.sandbox.codex.win.struct.FwpmTypes.FWPM_SUBLAYER0;

/**
 * fwpuclnt.dll 全量自映射（jna-platform 无此库；设计文档 §2.1/§3）。
 *
 * <p>对应 codex wfp.rs：引擎会话 → 事务三段（Begin/Add…/Commit，失败 Abort）→ 关引擎，
 * 12 条持久 filter（ICMP/DNS53/DoT853/SMB445/139 × v4/v6，FWP_ACTION_BLOCK，
 * 条件 ALE_USER_ID SD blob + 协议/端口），排在防火墙 COM 规则之后的第二道防线。
 * 返回 DWORD：0（NO_ERROR）成功，FWP_E_* HRESULT 见 {@link WinErr}。
 * 结构体见 {@code win.struct.FwpTypes}/{@code win.struct.FwpmTypes}。
 */
public interface Fwpuclnt extends StdCallLibrary {

    Fwpuclnt INSTANCE = Native.load("fwpuclnt", Fwpuclnt.class, W32APIOptions.UNICODE_OPTIONS);

    /**
     * FwpmEngineOpen0——wfp.rs::Engine::open：servername 传 null（本机）、
     * authnService 用 {@link #RPC_C_AUTHN_DEFAULT}、authIdentity null、
     * session 见 {@link FWPM_SESSION0}（txnWatchdogTimeoutInMSec）。
     */
    int FwpmEngineOpen0(String serverName, int authnService, Pointer authIdentity,
            FWPM_SESSION0 session, WinNT.HANDLEByReference engineHandle);

    /** FwpmEngineClose0——Engine drop：任何失败路径也要关引擎。 */
    int FwpmEngineClose0(WinNT.HANDLE engineHandle);

    /** FwpmTransactionBegin0——事务包裹（不变量③：WFP 事务 + 固定 GUID）。 */
    int FwpmTransactionBegin0(WinNT.HANDLE engineHandle, int flags);

    /** FwpmTransactionCommit0——提交（安装顺序 fail-closed，Commit 前一切可回滚）。 */
    int FwpmTransactionCommit0(WinNT.HANDLE engineHandle);

    /** FwpmTransactionAbort0——Abort 兜底（try/finally 中未提交即回滚）。 */
    int FwpmTransactionAbort0(WinNT.HANDLE engineHandle);

    /** FwpmProviderAdd0——ensure_provider（容 FWP_E_ALREADY_EXISTS）。 */
    int FwpmProviderAdd0(WinNT.HANDLE engineHandle, FWPM_PROVIDER0 provider, Pointer sd);

    /** FwpmProviderDeleteByKey0——remove_wfp_filters（容 NOT_FOUND）。 */
    int FwpmProviderDeleteByKey0(WinNT.HANDLE engineHandle, Guid.GUID key);

    /** FwpmSubLayerAdd0——ensure_sublayer（容 ALREADY_EXISTS）。 */
    int FwpmSubLayerAdd0(WinNT.HANDLE engineHandle, FWPM_SUBLAYER0 subLayer, Pointer sd);

    /** FwpmSubLayerDeleteByKey0——卸载路径（容 NOT_FOUND）。 */
    int FwpmSubLayerDeleteByKey0(WinNT.HANDLE engineHandle, Guid.GUID key);

    /**
     * FwpmFilterAdd0——add_filter：sd 传 null（用引擎默认安全描述符），
     * id 输出 filterId（可 null）。
     */
    int FwpmFilterAdd0(WinNT.HANDLE engineHandle, FWPM_FILTER0 filter, Pointer sd,
            LongByReference id);

    /** FwpmFilterDeleteByKey0——delete_filter_if_present（容 FWP_E_FILTER_NOT_FOUND/FWP_E_NOT_FOUND）。 */
    int FwpmFilterDeleteByKey0(WinNT.HANDLE engineHandle, Guid.GUID key);

    /** FwpmFreeMemory0——释放引擎输出缓冲（枚举场景；本插件主要用于对称性）。 */
    void FwpmFreeMemory0(PointerByReference entry);

    // ---- 常量（fwpmu.h/fwptypes.h；jna-platform 无 fwpuclnt） ----

    /** 认证服务：默认（wfp.rs::Engine::open 传值）。 */
    int RPC_C_AUTHN_DEFAULT = 0xFFFFFFFF;

    /** FWP_ACTION_TYPE：阻止（12 条 filter 全部 BLOCK）。 */
    int FWP_ACTION_BLOCK = 0x00000001;
    /** FWP_ACTION_TYPE：放行。 */
    int FWP_ACTION_PERMIT = 0x00000002;
    /** FWP_ACTION_TYPE：继续评估。 */
    int FWP_ACTION_CONTINUE = 0x00000004;
}
