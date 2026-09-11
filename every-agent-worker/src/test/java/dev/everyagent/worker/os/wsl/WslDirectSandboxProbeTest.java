package dev.everyagent.worker.os.wsl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WslDirectSandbox} 探测成功判定的契约:成败只看 stdout(id -u 输出纯 "0")。
 *
 * <p>回归场景:系统代理开启时 wsl.exe 会往 stderr 打「检测到 localhost 代理配置」警告,
 * 若把 stderr 混入判定会把可用的 wsl-direct 误判为失败并回退 windows-mic。
 * 修复后 runCapture 分离两路输出,probeOutputOk 只看 stdout——stderr 有警告也必须判定成功。
 */
class WslDirectSandboxProbeTest {

    @Test
    void probeOutputOkAcceptsPureZeroStdout() {
        assertTrue(WslDirectSandbox.probeOutputOk(0, "0"));
        assertTrue(WslDirectSandbox.probeOutputOk(0, "0\n"));
        assertTrue(WslDirectSandbox.probeOutputOk(0, " 0 "));
    }

    @Test
    void probeOutputOkIgnoresHarmlessWslStderrWarning() {
        // stderr 的 localhost 代理警告不得影响成败:rc=0 + stdout 纯 "0" 即成功
        assertTrue(WslDirectSandbox.probeOutputOk(0, "0"));
        assertTrue(WslDirectSandbox.probeOutputOk(0, "0\n"));
        // 即便诊断合并视图带警告,成功路径只看 stdout
        String stderrWarn = "wsl: 检测到 localhost 代理配置，但未镜像到 WSL。NAT 模式下的 WSL 不支持 localhost 代理。";
        assertTrue(WslDirectSandbox.probeOutputOk(0,
                WslBwrapSandbox.mergeDiagnostics("0", stderrWarn).split("\n")[0]));
    }

    @Test
    void probeOutputOkRejectsNonZeroOrNonZeroOutput() {
        // rc 非 0(真实失败)
        assertFalse(WslDirectSandbox.probeOutputOk(1, "0"));
        assertFalse(WslDirectSandbox.probeOutputOk(-1, "0"));
        // stdout 不是 "0"(命令产物异常)
        assertFalse(WslDirectSandbox.probeOutputOk(0, ""));
        assertFalse(WslDirectSandbox.probeOutputOk(0, "Not found"));
        assertFalse(WslDirectSandbox.probeOutputOk(0, "0 1"));
        // 错误码文案出现即使 rc=0 也是失败(bash -c 成败以 rc 为主,兜底拒绝)
        assertFalse(WslDirectSandbox.probeOutputOk(0, "不存在具有所提供名称的分发。"));
        assertFalse(WslDirectSandbox.probeOutputOk(0, "拒绝访问。"));
    }
}