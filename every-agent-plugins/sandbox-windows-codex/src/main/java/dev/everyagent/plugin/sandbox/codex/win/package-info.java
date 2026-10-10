/**
 * Win32 绑定层（设计文档 §2.1/§3）——「扩展 jna-platform 接口再 Native.load」
 * （对照 sandbox-windows-mic 的 Win32Ex），全部以 UNICODE_OPTIONS 加载。
 *
 * <p>本包只有纯 Java 声明：INSTANCE 静态字段的 Native.load 是运行时行为，
 * 非 Windows 平台不触发（Linux 下可编译，见设计文档 §8 测试策略）。
 *
 * <p><b>复用 jna-platform（不重映射）</b>：
 * <ul>
 *   <li>Kernel32：命名管道套件、CreateProcessW、CreateFile/CreateMutex、
 *       DuplicateHandle/SetHandleInformation、GetExitCodeProcess、SetErrorMode、
 *       QueryFullProcessImageNameW、TerminateProcess、LocalFree；</li>
 *   <li>Advapi32：CreateProcessWithLogonW、LogonUser、OpenProcessToken、
 *       GetTokenInformation、AdjustTokenPrivileges、LookupPrivilegeValue、
 *       LookupAccountName/Sid、ConvertSidToStringSid/ConvertStringSidToSid、
 *       Get/SetNamedSecurityInfo、Get/SetSecurityInfo、注册表读写键值；</li>
 *   <li>Netapi32：NetUserAdd/Del/GetInfo、NetApiBufferFree、NetGetDCName
 *       （LMAccess.USER_INFO_1/23、LOCALGROUP_INFO_0/1 结构体一并复用）；</li>
 *   <li>Ole32：CoInitializeEx/CoCreateInstance/CoUninitialize 已够用
 *       （COM vtable 手工调用经 Pointer 槽位，后续步骤）；</li>
 *   <li>Shell32：ShellExecuteEx + ShellAPI.SHELLEXECUTEINFO（UAC "runas"）；</li>
 *   <li>Crypt32：CryptProtectData/CryptUnprotectData（DPAPI，flags 可带 LOCAL_MACHINE）。</li>
 * </ul>
 *
 * <p><b>本包自映射</b>：{@link dev.everyagent.plugin.sandbox.codex.win.Kernel32Ex}
 * （Job Object/PROC_THREAD_ATTRIBUTE/CreateProcessAsUserW/CancelSynchronousIo）、
 * {@link dev.everyagent.plugin.sandbox.codex.win.Advapi32Ex}（受限令牌/ACL/SDDL）、
 * {@link dev.everyagent.plugin.sandbox.codex.win.NetApi32Ex}（本地组/SetInfo）、
 * {@link dev.everyagent.plugin.sandbox.codex.win.User32Ex}（私有桌面）、
 * {@link dev.everyagent.plugin.sandbox.codex.win.UserenvEx}（DeleteProfileW）、
 * {@link dev.everyagent.plugin.sandbox.codex.win.Fwpuclnt}（WFP 全量）、
 * {@link dev.everyagent.plugin.sandbox.codex.win.WinErr}（错误码表）。
 * 结构体在 {@code struct} 子包（Job/StartupInfoExW/ACL/FWP/FWPM）。
 */
package dev.everyagent.plugin.sandbox.codex.win;
