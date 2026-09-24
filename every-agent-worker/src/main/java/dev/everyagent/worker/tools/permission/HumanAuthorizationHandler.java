package dev.everyagent.worker.tools.permission;

import dev.everyagent.plugin.api.permission.AuthorizationChain;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationRequest;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationDecision;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
import dev.everyagent.worker.task.AgentCancelledException;
import dev.everyagent.worker.task.PendingAsks;
import dev.everyagent.worker.task.TaskEntry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 人工弹窗授权节点（core 提供，order=300，始终 applies=true）。
 *
 * <p>把原 GrantRegistry.askUser() 逻辑搬来：发起 authorization ask，
 * 解析答案返回 ALLOW/DENY（不返回 PASS——是链的终结节点）。
 */
@Component
public class HumanAuthorizationHandler implements AuthorizationHandler {

    static final List<String> AUTHORIZE_OPTIONS = List.of("本轮运行内允许", "本任务全程允许", "拒绝");

    private final AuthorizationHandlerRegistry registry;
    private final PendingAsks asks;
    private final WorkerProperties props;

    public HumanAuthorizationHandler(AuthorizationHandlerRegistry registry, PendingAsks asks, WorkerProperties props) {
        this.registry = registry;
        this.asks = asks;
        this.props = props;
    }

    @PostConstruct
    void selfRegister() {
        registry.register(this);
    }

    @Override
    public String id() { return "human-authorization"; }

    @Override
    public float order() { return 300f; }

    @Override
    public AuthorizationDecision invoke(AuthorizationRequest req, AuthorizationChain next) throws Exception {
        TaskEntry t = (TaskEntry) req.task();
        List<PendingAsks.AskQuestion> questions = List.of(
                new PendingAsks.AskQuestion("", req.prompt(), AUTHORIZE_OPTIONS));
        PendingAsks.AskAnswer ans;
        try {
            ans = asks.ask(t.events, t.taskId, req.agentId(), "authorization", questions,
                    props.getPermissions().getAuthTimeoutMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentCancelledException("task cancelled");
        }
        if (!"answered".equals(ans.status())) {
            return new AuthorizationDecision(AuthorizationDecision.Type.DENY, "拒绝");
        }
        GrantScope scope = GrantRegistry.parseScope(ans.text());
        return switch (scope) {
            case RUN, TASK -> new AuthorizationDecision(AuthorizationDecision.Type.ALLOW, "人工授权");
            case DENY -> new AuthorizationDecision(AuthorizationDecision.Type.DENY, "拒绝");
        };
    }
}
