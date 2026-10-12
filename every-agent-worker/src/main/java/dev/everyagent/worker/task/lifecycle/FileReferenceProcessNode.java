package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.attachment.UserInputMaterializer;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.spi.FileReferenceContext;
import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.worker.plugin.registry.FileReferenceHandlerRegistry;

import java.nio.file.Path;
import java.util.Map;

/**
 * 文件引用处理节点（order=875，临界段内侧·轮次循环段，consume.input=880 之前，与 EditResendNode=877 错开）。
 *
 * <p>职责（薄壳节点，核心对具体文件类型零感知）：把本轮的 {@code ctx.input()} + {@code ctx.rawContent()}
 * 交给 {@link UserInputMaterializer} 物化——token 解析、handler 分发（按扩展名查
 * {@link FileReferenceHandlerRegistry}）、正文改写、附件合并进 {@code TaskEntry.metadata.attachments}
 * （随 meta.json 持久化，跨轮可读，由 {@code FileAttachmentAdvisor} 注入模型）全在物化服务内完成，
 * 与「插入到当前对话」等不经本节点的投递路径**共用同一实现**（历史上两者各写一份 → 插入路径的
 * @ 图片退化成裸路径文本，AI 只看到路径、转而去 read_file 图片失败）。
 *
 * <p>本节点只需改写后的正文：消息本身由 {@code consume.input} 从 {@code ctx.input()} + metadata 重建，
 * 故物化结果的消息形态不予使用。
 *
 * <p>未命中 handler / handler 返回 null / 处理抛异常 → 不改 input（路径文本保持现有行为），
 * 绝不阻断任务。rawContent 不改写（前端回放仍用原 token 还原胶囊）。
 */
public final class FileReferenceProcessNode implements TaskLifecycleNode {

    private final UserInputMaterializer materializer;
    private final FileReferenceHandlerRegistry handlers;

    public FileReferenceProcessNode(UserInputMaterializer materializer,
            FileReferenceHandlerRegistry handlers) {
        this.materializer = materializer;
        this.handlers = handlers;
    }

    @Override
    public String id() {
        return "file.reference.process";
    }

    @Override
    public float order() {
        return 875f;
    }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        processReferences(ctx);
        return next.proceed(ctx);
    }

    /** 下行段：委托核心物化服务（解析 rawContent 文件引用 → 分发 handler → 改写正文 + 合并附件）。 */
    private void processReferences(TaskLifecycleContext ctx) {
        String raw = ctx.rawContent();
        String input = ctx.input();
        if (input == null || raw == null || raw.isEmpty() || handlers.isEmpty()) {
            return; // 无引用 / 无处理器：零成本短路（物化结果必然是正文原样）
        }
        String materialized = materializer.materialize(input, raw, new ContextView(ctx)).getText();
        if (materialized != null && !input.equals(materialized)) {
            ctx.input(materialized);
        }
    }

    /**
     * per-任务的 ExecContext 只读视图。
     *
     * <p>持有委托 {@link ExecContext}（即 TaskRuntime；早期节点任务尚未创建时为
     * null）+ 早期回退字段（taskId / workspaceId / workspaceRoot 字符串）。
     * {@link ExecContext} 非空时槽位一律委托；为 null 时早期回退字段供给
     * {@code subjectId()} / {@code workspaceId()} / {@code workspaceRoot()}，
     * 其余槽位（snapshot/emitter/agentFactory/interaction/dataDir/agents）回退为
     * null（terminal 回退 false、metadata/agents 回退空 Map）。
     */
    private static final class ContextView implements FileReferenceContext {

        private final ExecContext exec;
        private final String earlyTaskId;
        private final String earlyWorkspaceId;
        private final String earlyWorkspaceRoot;

        ContextView(TaskLifecycleContext ctx) {
            TaskRuntime runtime = ctx.taskRuntime();
            this.exec = runtime; // TaskRuntime implements ExecContext
            this.earlyTaskId = ctx.taskId();
            this.earlyWorkspaceId = ctx.workspaceId();
            this.earlyWorkspaceRoot = ctx.workspaceRoot();
        }

        @Override
        public String subjectId() {
            return exec != null ? exec.subjectId() : earlyTaskId;
        }

        @Override
        public String workspaceRoot() {
            return exec != null ? exec.workspaceRoot() : earlyWorkspaceRoot;
        }

        @Override
        public String workspaceId() {
            return exec != null ? exec.workspaceId() : earlyWorkspaceId;
        }

        @Override
        public ModelConfig snapshot() {
            return exec != null ? exec.snapshot() : null;
        }

        @Override
        public EventEmitter emitter() {
            return exec != null ? exec.emitter() : null;
        }

        @Override
        public AgentFactory agentFactory() {
            return exec != null ? exec.agentFactory() : null;
        }

        @Override
        public Map<String, Object> metadata() {
            return exec != null ? exec.metadata() : Map.of();
        }

        @Override
        public Path dataDir() {
            return exec != null ? exec.dataDir() : null;
        }

        @Override
        public boolean terminal() {
            return exec != null && exec.terminal();
        }

        @Override
        public InteractionService interaction() {
            return exec != null ? exec.interaction() : null;
        }

        @Override
        public Map<String, AgentContext> agents() {
            return exec != null ? exec.agents() : Map.of();
        }
    }
}
