package dev.everyagent.plugin.unattended;

import dev.everyagent.plugin.api.permission.AuthorizationChain;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;

public class UnattendedAuthHandler implements AuthorizationHandler {

    public UnattendedAuthHandler() {
    }
    @Override
    public String id() { return "unattended-auth"; }

    @Override
    public float order() { return 200f; }

    @Override
    public AuthorizationDecision invoke(AuthorizationRequest req, AuthorizationChain next) throws Exception {
        if (!Boolean.TRUE.equals(req.context().metadata().getOrDefault("unattended", false))) {
            return next.proceed(req);
        }
        return new AuthorizationDecision(AuthorizationDecision.Type.DENY, "无人值守模式拒绝授权");
    }
}
