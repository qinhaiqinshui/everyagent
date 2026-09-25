package dev.everyagent.plugin.aireview;

import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
import dev.everyagent.plugin.api.permission.AuthorizationChain;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationRequest;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationDecision;
import org.springframework.stereotype.Component;

@Component
public class AiReviewAuthHandler implements AuthorizationHandler {
    private final AiAuthReviewer reviewer;

    public AiReviewAuthHandler(AuthorizationHandlerRegistry registry, AiAuthReviewer reviewer) {
        this.reviewer = reviewer;
        registry.register(this);
    }

    @Override
    public String id() { return "ai-review-auth"; }

    @Override
    public float order() { return 100f; }

    @Override
    public AuthorizationDecision invoke(AuthorizationRequest req, AuthorizationChain next) throws Exception {
        if (!req.task().taskFlags().getOrDefault("ai-review", false)) {
            return next.proceed(req); // 不适用，放行
        }
        ReviewDecision d = reviewer.review((dev.everyagent.worker.task.TaskEntry) req.task(), req.grantKey(), req.prompt());
        if (d.fallback()) {
            return next.proceed(req); // PASS
        }
        return switch (d.verdict()) {
            case ALLOW    -> new AuthorizationDecision(AuthorizationDecision.Type.ALLOW, d.reason());
            case DENY     -> new AuthorizationDecision(AuthorizationDecision.Type.DENY, d.reason());
            case ESCALATE -> next.proceed(req); // ESCALATE → PASS → 放行链
        };
    }
}
