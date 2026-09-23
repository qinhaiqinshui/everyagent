package dev.everyagent.plugin.authreview;

import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
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
    public int order() { return 100; }

    @Override
    public boolean applies(AuthorizationRequest req) {
        return req.task().taskFlags().getOrDefault("ai-review", false);
    }

    @Override
    public AuthorizationDecision decide(AuthorizationRequest req) {
        ReviewDecision d = reviewer.review((dev.everyagent.worker.task.TaskEntry) req.task(), req.grantKey(), req.prompt());
        if (d.fallback()) return new AuthorizationDecision(AuthorizationDecision.Type.PASS, d.reason());
        return switch (d.verdict()) {
            case ALLOW    -> new AuthorizationDecision(AuthorizationDecision.Type.ALLOW, d.reason());
            case DENY     -> new AuthorizationDecision(AuthorizationDecision.Type.DENY, d.reason());
            case ESCALATE -> new AuthorizationDecision(AuthorizationDecision.Type.PASS, d.reason());
        };
    }
}
