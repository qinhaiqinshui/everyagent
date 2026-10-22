package dev.everyagent.worker.tools;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.plugin.registry.SandboxProviderRegistry;
import dev.everyagent.worker.tools.permission.AuthorizeCheck;
import dev.everyagent.worker.tools.permission.CommandCheck;
import dev.everyagent.worker.tools.permission.ExternalRootAllowCheck;
import dev.everyagent.worker.tools.permission.GrantRegistry;
import dev.everyagent.worker.tools.permission.MissingPathCheck;
import dev.everyagent.worker.tools.permission.OverBroadRootCheck;
import dev.everyagent.worker.tools.permission.PrivilegeCheck;
import dev.everyagent.worker.tools.permission.SkillsReadAllowCheck;
import dev.everyagent.worker.tools.permission.WorkspaceAllowCheck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计 L-3 回归:{@code [exec] ... cmd=} 命令审计日志经 {@code SecretPatterns.maskSecrets}
 * 掩码(Bearer/sk- 等形态打码),命令结构、门禁与执行结果零改动。DIRECT 路径端到端:
 * 门禁(良性 echo 命令直接放行)→ OsSandbox 直跑 cmd.exe → logback ListAppender 捕获审计行。
 */
class CommandExecutorLogMaskTest {

    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(CommandExecutor.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    void execAuditLogMasksCredentialShapesInCmd(@TempDir Path ws) throws Exception {
        Path realWs = ws.toRealPath();
        ExecContext task = mock(ExecContext.class);
        when(task.workspaceRoot()).thenReturn(realWs.toString());
        when(task.subjectId()).thenReturn("t-mask-test");
        WorkspaceManager workspaces = mock(WorkspaceManager.class);
        when(workspaces.resolve(anyString())).thenReturn(new WorkspaceManager.Root(realWs, realWs));
        when(workspaces.externalRootsOf(anyString())).thenReturn(List.of());
        // 静态判定面:良性 echo(无危险动词、无路径候选)不触发授权;PermissionGate 构造的
        // List.of 不收 null,链上非被测节点一律 mock 占位(本测试只走 cmdChain)
        PermissionGate gate = new PermissionGate(workspaces, mock(GrantRegistry.class),
                mock(WorkspaceAllowCheck.class), mock(MissingPathCheck.class),
                mock(SkillsReadAllowCheck.class), mock(ExternalRootAllowCheck.class),
                mock(OverBroadRootCheck.class), mock(AuthorizeCheck.class),
                new CommandCheck(new WorkerProperties(), workspaces, null),
                mock(PrivilegeCheck.class));
        CommandExecutor executor = new CommandExecutor(
                new OsSandbox(new WorkerProperties(), new SandboxProviderRegistry()),
                task, gate, "a-mask-test");

        String anthropicKey = "sk-ant-sid01-" + "A".repeat(58) + "6280";
        String bearer = "abcdef0123456789ABCD";
        String command = "echo exec-mask-probe && echo \"Authorization: Bearer " + bearer
                + "\" && echo " + anthropicKey;

        appender.start();
        logger.addAppender(appender);
        String result = executor.execute(command, "cmd");

        String auditLine = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(s -> s.contains("[exec]"))
                .collect(Collectors.joining("\n"));
        assertTrue(auditLine.contains("backend=direct"), "DIRECT 审计行: " + auditLine);
        assertTrue(auditLine.contains("cmd=echo exec-mask-probe"), "命令结构保留: " + auditLine);
        assertTrue(auditLine.contains("Bearer ****"), "Bearer 本体打码: " + auditLine);
        assertTrue(auditLine.contains("sk-****"), "sk- 保留格式前缀、本体打码: " + auditLine);
        assertFalse(auditLine.contains(bearer), "Bearer 原文不得落审计日志");
        assertFalse(auditLine.contains(anthropicKey), "密钥原文不得落审计日志");
        assertFalse(auditLine.contains("sid01"), "格式中段也是密钥本体: " + auditLine);
        assertTrue(result.contains("exec-mask-probe"), "执行结果(业务面)不受日志掩码影响");
    }
}
