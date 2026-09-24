package dev.everyagent.plugin.authreview;

import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
import dev.everyagent.plugin.api.permission.AuthorizationChain;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationRequest;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationDecision;
import org.springframework.stereotype.Component;

@Component
public class UnattendedAuthHandler implements AuthorizationHandler {

    public UnattendedAuthHandler(AuthorizationHandlerRegistry registry) {
        registry.register(this);
    }
    @Override
    public String id() { return "unattended-auth"; }

    @Override
    public float order() { return 200f; }

    @Override
    public AuthorizationDecision invoke(AuthorizationRequest req, AuthorizationChain next) throws Exception {
        if (!req.task().taskFlags().getOrDefault("unattended", false)) {
            return next.proceed(req);
        }
        return new AuthorizationDecision(AuthorizationDecision.Type.DENY, "无人值守模式拒绝授权");
    }
}
