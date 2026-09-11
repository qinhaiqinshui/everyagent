package dev.everyagent.worker.tools;

import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.os.OsSandbox.ExecResult;
import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import dev.everyagent.worker.task.TaskEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CommandExecutor 的 powershell 方言路由单测:WSL 后端下 powershell 命令<b>强制回宿主
 * Windows 原生沙箱</b>(spawnSandboxedWindows),不落入 wsl 沙箱(bash 才进 WSL);
 * bash 命令保持后端方言(spawnSandboxed)。powershell 授权检查喂原始命令(不做 WSL 路径翻译)。
 */
class CommandExecutorPowershellNativeTest {

    private static final String WS = "ws";
    private static final String AGENT = "agent";

    private static TaskEntry newTask() {
        ModelSnapshot snap = new ModelSnapshot("cfg", "openai-compat",
                "http://localhost:9999/v1", "m", null);
        return new TaskEntry("t-1", "任务", snap, "k", WS, "main-agent", 10_000);
    }

    /** WSL 后端 mock:isWslBackend=true,isWslDirect=false,非 windows-mic 生效态。 */
    private static OsSandbox wslSandbox() {
        OsSandbox s = mock(OsSandbox.class);
        when(s.isWslBackend()).thenReturn(true);
        when(s.isWslDirect()).thenReturn(false);
        when(s.isWindowsSandboxActive()).thenReturn(false);
        when(s.isWslBwrap()).thenReturn(false);
        when(s.networkAllowedByDefault()).thenReturn(true);
        when(s.privilegeAllowedByDefault()).thenReturn(true);
        return s;
    }

    @Test
    void powershellInWslBackendForcesWindowsNativeSandbox() throws Exception {
        OsSandbox sandbox = wslSandbox();
        when(sandbox.spawnSandboxedWindows(anyString(), any(), any(), anyString(), any(), anyBoolean(),
                anyBoolean()))
                .thenReturn(new ExecResult("PSVersion 5.1.19041", "", 0, false));
        PermissionGate gate = mock(PermissionGate.class);
        TaskEntry task = newTask();
        CommandExecutor exec = new CommandExecutor(sandbox, task, gate, AGENT);

        String out = exec.execute("$PSVersionTable | Out-String", "powershell");

        assertTrue(out.contains("PSVersion"), "应返回 Windows 原生沙箱的执行结果: " + out);
        // powershell 必须走 spawnSandboxedWindows,绝不落入 wsl 沙箱(spawnSandboxed / seccomp)
        verify(sandbox).spawnSandboxedWindows(anyString(), any(), any(), anyString(), any(), anyBoolean(),
                anyBoolean());
        verify(sandbox, never()).spawnSandboxed(anyString(), any(), any(), anyString(), any(), anyBoolean(),
                anyBoolean());
        verify(sandbox, never()).spawnSandboxedSeccomp(anyString(), any(), any(), anyString(), any(),
                anyBoolean(), any());
        // 授权检查喂原始命令(WSL 后端不翻译 powershell;bash 才翻译)
        verify(gate).requireCommand(task, AGENT, "$PSVersionTable | Out-String");
    }

    @Test
    void powershellSpawnCommandCarriesPreferencePrefix() throws Exception {
        OsSandbox sandbox = wslSandbox();
        when(sandbox.spawnSandboxedWindows(anyString(), any(), any(), anyString(), any(), anyBoolean(),
                anyBoolean()))
                .thenReturn(new ExecResult("", "", 0, false));
        CommandExecutor exec = new CommandExecutor(sandbox, newTask(),
                mock(PermissionGate.class), AGENT);

        exec.execute("Get-Date", "powershell");

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sandbox).spawnSandboxedWindows(captor.capture(), any(), any(), anyString(), any(),
                anyBoolean(), anyBoolean());
        String spawnCmd = captor.getValue();
        assertTrue(spawnCmd.startsWith("$ProgressPreference="), "应预置非成功流抑制前缀: " + spawnCmd);
        assertTrue(spawnCmd.contains("Get-Date"), "用户命令原样保留: " + spawnCmd);
    }

    @Test
    void bashInWslBackendStaysInWslSandbox() throws Exception {
        OsSandbox sandbox = wslSandbox();
        when(sandbox.spawnSandboxed(anyString(), any(), any(), anyString(), any(), anyBoolean(),
                anyBoolean()))
                .thenReturn(new ExecResult("ok", "", 0, false));
        PermissionGate gate = mock(PermissionGate.class);
        CommandExecutor exec = new CommandExecutor(sandbox, newTask(), gate, AGENT);

        String out = exec.execute("ls -la", "bash");

        assertEquals("ok", out);
        // bash 保持后端方言:走 spawnSandboxed(落入 wsl),绝不强制 Windows 原生
        verify(sandbox).spawnSandboxed(anyString(), any(), any(), anyString(), any(), anyBoolean(),
                anyBoolean());
        verify(sandbox, never()).spawnSandboxedWindows(anyString(), any(), any(), anyString(), any(),
                anyBoolean(), anyBoolean());
    }
}
