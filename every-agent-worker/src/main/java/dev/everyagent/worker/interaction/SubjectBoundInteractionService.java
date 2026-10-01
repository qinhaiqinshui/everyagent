package dev.everyagent.worker.interaction;

import dev.everyagent.plugin.api.interaction.AskQuestion;
import dev.everyagent.plugin.api.interaction.AskResult;
import dev.everyagent.plugin.api.interaction.InteractionService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 绑定执行主体 ID 的 {@link InteractionService} 静态代理 ——
 * {@code ExecContext.interaction()} 槽位的 worker 实现（设计 §4.1）。
 *
 * <p>包装裸 {@code InteractionService}（worker 单例，非主体绑定）：{@link #ask} /
 * {@link #askAsync} 的 context map 为 null 或缺 {@code "taskId"} 键时自动填
 * {@code "taskId" → subjectId}（wire 兼容：键名不变，值今天 = taskId），调用方其余键
 * 原样保留；替代 HumanAuthorizationHandler / ImageReferenceHandler / AskUserTool
 * 各自手动组装 {@code Map.of("taskId",...)}。agentId 等其余标记由调用方按需经
 * context 参数补充。{@link #hasPendingFor} 无主体维度，原样透传。
 *
 * <p>TaskEntry（worker 内部具体类）在 {@code interaction()} 首调时直接 new 本代理
 * 懒加载缓存（§4.5：worker 内部不做接口抽象体操）。
 */
public final class SubjectBoundInteractionService implements InteractionService {

    private final InteractionService delegate;
    private final String subjectId;

    public SubjectBoundInteractionService(InteractionService delegate, String subjectId) {
        this.delegate = delegate;
        this.subjectId = subjectId;
    }

    @Override
    public AskResult ask(List<AskQuestion> questions, long timeoutMs, Map<String, String> context)
            throws InterruptedException {
        return delegate.ask(questions, timeoutMs, withSubject(context));
    }

    @Override
    public void askAsync(List<AskQuestion> questions, long timeoutMs, Map<String, String> context,
            Consumer<AskResult> callback) {
        delegate.askAsync(questions, timeoutMs, withSubject(context), callback);
    }

    @Override
    public boolean hasPendingFor(String taskId, String agentId) {
        return delegate.hasPendingFor(taskId, agentId);
    }

    /**
     * 补填主体标记：context 为 null → 新建 {@code {"taskId": subjectId}}；
     * 缺 "taskId" 键 → 复制补填（其余键保留）；已含 → 原样返回（调用方显式值优先）。
     */
    private Map<String, String> withSubject(Map<String, String> context) {
        if (context == null) {
            return Map.of("taskId", subjectId);
        }
        if (context.containsKey("taskId")) {
            return context;
        }
        Map<String, String> filled = new HashMap<>(context);
        filled.put("taskId", subjectId);
        return filled;
    }
}
