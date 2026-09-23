package dev.everyagent.plugin.authreview;

import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
import dev.everyagent.worker.tools.permission.AuthorizationHandler;
import dev.everyagent.worker.tools.permission.AuthorizationHandler.AuthorizationRequest;
import dev.everyagent.worker.tools.permission.AuthorizationHandler.AuthorizationDecision;
import org.springframework.stereotype.Component;

@Component
public class UnattendedAuthHandler implements AuthorizationHandler {

    public UnattendedAuthHandler(AuthorizationHandlerRegistry registry) {
        registry.register(this);
    }
    @Override
    public int order() { return 200; }

    @Override
    public boolean applies(AuthorizationRequest req) {
        return req.task().taskFlags.getOrDefault("unattended", false);
    }

    @Override
    public AuthorizationDecision decide(AuthorizationRequest req) {
        return new AuthorizationDecision(AuthorizationDecision.Type.DENY, "无人值守模式拒绝授权");
    }
}
