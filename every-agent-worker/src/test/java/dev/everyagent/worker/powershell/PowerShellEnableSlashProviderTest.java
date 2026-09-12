package dev.everyagent.worker.powershell;

import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import dev.everyagent.worker.slash.SlashCommandItem;
import dev.everyagent.worker.slash.SlashCommandRegistry;
import dev.everyagent.worker.slash.SlashDisplayPosition;
import dev.everyagent.worker.slash.SlashSelectionResult;
import dev.everyagent.worker.slash.SlashTokenEncoder;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PowerShellEnableSlashProvider 单测:`/` 候选 id=powershell-enable:on;
 * 仅 WSL+Linux 后端(wsl-bwrap / wsl-direct)注册,windows-mic(Windows+ACL)后端不注册;
 * select 返回 1 个 bottom 结果(token 可 parse 为 kind=powershell.enable);
 * taskId 非空时置 {@code TaskEntry.powershellEnabled=true} 并落盘,为空时不写业务标记仍返回胶囊;
 * cancel 复位 powershellEnabled 并落盘。
 */
class PowerShellEnableSlashProviderTest {

    private SlashCommandRegistry registry;
    private TaskManager taskManager;
    private TaskEntry task;
    private AtomicInteger persisted;

    @BeforeEach
    void setUp() {
        registry = new SlashCommandRegistry();
        taskManager = mock(TaskManager.class);
        task = newTask();
        persisted = new AtomicInteger();
        task.persistHook = persisted::incrementAndGet;
        when(taskManager.runningTask("t-1")).thenReturn(task);
    }

    private static TaskEntry newTask() {
        ModelSnapshot snap = new ModelSnapshot("cfg", "openai-compat",
                "http://localhost:9999/v1", "m", null);
        return new TaskEntry("t-1", "任务", snap, "k", "ws", "main-agent", 10_000);
    }

    private static OsSandbox sandbox(boolean wslBackend) {
        OsSandbox s = mock(OsSandbox.class);
        when(s.isWslBackend()).thenReturn(wslBackend);
        return s;
    }

    private SlashCommandItem item() {
        new PowerShellEnableSlashProvider(registry, taskManager, sandbox(true));
        return registry.list().stream()
                .filter(i -> "powershell-enable:on".equals(i.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未注册 powershell-enable:on 条目"));
    }

    // ---- 条件注册:仅 WSL+Linux 后端注册 ----

    @Test
    void registersOnlyOnWslBackend() {
        new PowerShellEnableSlashProvider(registry, taskManager, sandbox(true));
        assertFalse(registry.list().isEmpty(), "WSL+Linux 后端应注册 /启用powershell");
        assertTrue(registry.list().stream().anyMatch(i -> "powershell-enable:on".equals(i.id())));
    }

    @Test
    void doesNotRegisterOnWindowsMicBackend() {
        new PowerShellEnableSlashProvider(registry, taskManager, sandbox(false));
        assertTrue(registry.list().isEmpty(), "windows-mic(Windows+ACL)后端不应注册 /启用powershell");
    }

    // ---- 候选 ----

    @Test
    void itemsExposePowerShellEnableCandidate() {
        SlashCommandItem it = item();
        assertEquals("powershell-enable:on", it.id());
        assertEquals("启用powershell", it.title());
        assertTrue(PowerShellEnableToken.enabledIn(it.insertText()), "insertText 应为启用 powershell opaque token");
    }

    // ---- select:单个 bottom 胶囊 ----

    @Test
    void selectReturnsSingleBottomCapsuleAndTurnsOnPowerShell() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "t-1");

        assertEquals(1, results.size(), "启用 powershell onSelect 应返回单胶囊");
        SlashSelectionResult r = results.get(0);
        assertEquals(SlashDisplayPosition.BOTTOM, r.position(), "启用 powershell 胶囊 bottom 渲染");
        SlashTokenEncoder.ParsedToken tok = SlashTokenEncoder.parseToken(r.token());
        assertNotNull(tok, "token 可 parse");
        assertEquals(PowerShellEnableToken.KIND, tok.kind(), "token kind=powershell.enable");

        verify(taskManager).runningTask("t-1");
        assertTrue(task.powershellEnabled, "业务标记 powershellEnabled 应置位");
        assertTrue(persisted.get() >= 1, "置位后应落盘 meta");
    }

    // ---- select:空 taskId 不写业务标记 ----

    @Test
    void selectWithEmptyTaskIdStillReturnsCapsuleWithoutBusinessMark() {
        SlashCommandItem it = item();
        List<SlashSelectionResult> results = it.selectHandler().onSelect(it, "");

        assertEquals(1, results.size(), "空 taskId(草稿态)仍返回胶囊");
        assertEquals(SlashDisplayPosition.BOTTOM, results.get(0).position());
        verify(taskManager, never()).runningTask(any());
        assertFalse(task.powershellEnabled, "空 taskId 不写业务标记");
        assertEquals(0, persisted.get(), "空 taskId 不落盘");
    }

    // ---- cancel:复位 powershellEnabled ----

    @Test
    void cancelTurnsPowerShellOff() {
        task.powershellEnabled = true;
        SlashCommandItem it = item();
        it.cancelHandler().onCancel(it, PowerShellEnableToken.buildToken(), "t-1");
        assertFalse(task.powershellEnabled, "取消启用 powershell 胶囊应复位 powershellEnabled");
        assertTrue(persisted.get() >= 1, "取消后应落盘 meta");
    }
}