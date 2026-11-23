// ea: WorkflowSubject 三通道判定专项测试（通道③布局兜底：真实 instances 层级 + 两级上限）
package dev.everyagent.plugin.workflow.engine;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通道③布局兜底判定：实例目录真实布局为
 * {@code workspaces/<wsId>/workflows/instances/<wiId>}（dataDir 的 parent=instances、
 * grandparent=workflows）——向上最多两级（parent/grandparent）命中目录名 workflows；
 * 直接父级即 workflows 的同域路径兼容；task 域与更深的同名目录不误伤。
 * 桩为<b>最小 ExecContext</b>（仅 dataDir 生效，其余槽位 null——通道①②因此不触发，
 * 判定结果即通道③的独立输出）。
 */
class WorkflowSubjectTest {

    @TempDir
    Path home;

    /** 最小 ExecContext 桩：除 dataDir 外全部 null（通道①②不触发，只测通道③）。 */
    private static ExecContext ctxWithDataDir(Path dataDir) {
        return new ExecContext() {
            @Override public String subjectId() { return "wi_x"; }
            @Override public String workspaceRoot() { return null; }
            @Override public String workspaceId() { return null; }
            @Override public ModelConfig snapshot() { return null; }
            @Override public EventEmitter emitter() { return null; }
            @Override public AgentFactory agentFactory() { return null; }
            @Override public Map<String, Object> metadata() { return null; }
            @Override public Path dataDir() { return dataDir; }
            @Override public boolean terminal() { return false; }
            @Override public InteractionService interaction() { return null; }
            @Override public Map<String, AgentContext> agents() { return null; }
        };
    }

    @Test
    void channel3MatchesRealInstanceLayout() {
        // 真实实例目录（store.createInstance 的产物布局）：workflows 在 grandparent 层
        Path instanceDir = home.resolve("workspaces").resolve("ws_a").resolve("workflows")
                .resolve("instances").resolve("wi_x");
        assertThat(WorkflowSubject.isWorkflowSubject(ctxWithDataDir(instanceDir))).isTrue();
    }

    @Test
    void channel3KeepsDirectParentCompat() {
        // 直接父级即 workflows 的同域路径（如 definitions/<wfId> 形态）兼容命中
        Path direct = home.resolve("workspaces").resolve("ws_a").resolve("workflows")
                .resolve("wf_y");
        assertThat(WorkflowSubject.isWorkflowSubject(ctxWithDataDir(direct))).isTrue();
    }

    @Test
    void channel3RejectsTaskAndUnrelatedLayouts() {
        // task 域布局：互斥不命中
        assertThat(WorkflowSubject.isWorkflowSubject(ctxWithDataDir(
                home.resolve("workspaces").resolve("ws_a").resolve("tasks").resolve("t_x"))))
                .isFalse();
        // 与工作流无关的普通目录
        assertThat(WorkflowSubject.isWorkflowSubject(ctxWithDataDir(
                home.resolve("proj").resolve("src")))).isFalse();
    }

    @Test
    void channel3CapsAncestorSearchAtTwoLevels() {
        // workflows 在三级之上：不命中（两级上限，防深路径误伤）
        Path tooDeep = home.resolve("workspaces").resolve("ws_a").resolve("workflows")
                .resolve("a").resolve("b").resolve("c");
        assertThat(WorkflowSubject.isWorkflowSubject(ctxWithDataDir(tooDeep))).isFalse();
        // 实例目录的子目录（如 agents/）对 dataDir 契约而言不是实例目录本身：
        // workflows 同样在三级之上 → 不命中（dataDir 契约=实例目录，此为文档化边界）
        Path subDir = home.resolve("workspaces").resolve("ws_a").resolve("workflows")
                .resolve("instances").resolve("wi_x").resolve("agents");
        assertThat(WorkflowSubject.isWorkflowSubject(ctxWithDataDir(subDir))).isFalse();
    }

    @Test
    void nullAndEmptyInputsSafe() {
        assertThat(WorkflowSubject.isWorkflowSubject(null)).isFalse();
        assertThat(WorkflowSubject.isWorkflowSubject(ctxWithDataDir(null))).isFalse();
        // 相对路径（无足够祖先）不抛不命中
        assertThat(WorkflowSubject.isWorkflowSubject(ctxWithDataDir(Path.of("wi_x")))).isFalse();
    }
}
