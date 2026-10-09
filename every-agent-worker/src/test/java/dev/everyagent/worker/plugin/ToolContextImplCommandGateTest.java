package dev.everyagent.worker.plugin;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.tools.PermissionDeniedException;
import dev.everyagent.worker.tools.PermissionGate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * {@link ToolContextImpl#commandGate()} 契约单测（§7.8）。
 *
 * <p>沙箱插件自建命令执行器时经此门禁接入 worker 的授权决议链。三条:
 * <ol>
 *   <li>放行：{@code PermissionGate.requireCommand} 不抛 → 门禁静默通过；</li>
 *   <li>拒绝：{@code PermissionDeniedException} 原样上抛（message 回灌模型），命令不执行；</li>
 *   <li>IO 异常：包装成 RuntimeException，不让 IOException 穿过 SPI 边界。</li>
 * </ol>
 */
class ToolContextImplCommandGateTest {

    private ToolContextImpl ctx(PermissionGate gate) {
        ExecContext exec = mock(ExecContext.class);
        ModelConfig snap = new ModelConfig("c", "openai-compat", "http://x", "m", null);
        org.mockito.Mockito.when(exec.snapshot()).thenReturn(snap);
        return new ToolContextImpl(exec, "main-agent", null, gate,
                mock(WorkspaceManager.class), null, null);
    }

    @Test
    void allowsWhenGateAllows() throws Exception {
        PermissionGate gate = mock(PermissionGate.class);
        assertDoesNotThrow(() -> ctx(gate).commandGate().authorize("dir"));
    }

    @Test
    void propagatesDenialWithMessage() throws Exception {
        PermissionGate gate = mock(PermissionGate.class);
        doThrow(new PermissionDeniedException("拒绝: 命令触及工作区外路径"))
                .when(gate).requireCommand(any(), anyString(), anyString());

        ToolContextImpl c = ctx(gate);
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> c.commandGate().authorize("Remove-Item C:\\other\\x.txt"));
        assertTrueContains(e.getMessage(), "工作区外", "拒绝原因必须透传给模型");
    }

    @Test
    void wrapsIoExceptionAsRuntime() throws Exception {
        PermissionGate gate = mock(PermissionGate.class);
        doThrow(new java.io.IOException("realpath 失败"))
                .when(gate).requireCommand(any(), anyString(), anyString());

        ToolContextImpl c = ctx(gate);
        RuntimeException e = assertThrows(RuntimeException.class, () -> c.commandGate().authorize("x"));
        assertTrueContains(e.getMessage(), "授权检查失败", "IO 异常需包装为运行时异常");
    }

    private static void assertTrueContains(String actual, String needle, String msg) {
        org.junit.jupiter.api.Assertions.assertNotNull(actual, msg);
        org.junit.jupiter.api.Assertions.assertTrue(actual.contains(needle),
                msg + " 实际: " + actual);
    }
}