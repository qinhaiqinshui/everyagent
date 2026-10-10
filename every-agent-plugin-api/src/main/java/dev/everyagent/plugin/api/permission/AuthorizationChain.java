package dev.everyagent.plugin.api.permission;

/**
 * 授权决议链的下一环。
 */
@FunctionalInterface
public interface AuthorizationChain {
    AuthorizationHandler.AuthorizationDecision proceed(AuthorizationHandler.AuthorizationRequest req) throws Exception;
}
