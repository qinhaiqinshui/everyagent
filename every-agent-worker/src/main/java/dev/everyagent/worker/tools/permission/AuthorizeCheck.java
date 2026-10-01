package dev.everyagent.worker.tools.permission;

import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationRequest;
import dev.everyagent.worker.tools.PermissionDeniedException;

import org.springframework.stereotype.Component;

/**
 * 责任链节点 4(文件路径链末端):把「需要授权」的请求交给 {@link GrantRegistry}
 * 的授权决议链(AI 审议 → 无人值守 → 人工弹窗)。已授权直接 ALLOW;决议放行 ALLOW;
 * 拒绝捕获 {@link PermissionDeniedException} 转 DENY。无 grantKey 视为无法处理(SKIP)。
 */
@Component
public class AuthorizeCheck implements PermissionCheck {

    private final GrantRegistry grants;

    public AuthorizeCheck(GrantRegistry grants) {
        this.grants = grants;
    }

    @Override
    public PermissionDecision invoke(PermissionContext ctx, PermissionChain next) {
        if (ctx.grantKey() == null || ctx.grantKey().isBlank()) {
            return next.proceed(ctx);
        }
        try {
            AuthorizationRequest req = ctx.authReq() != null ? ctx.authReq()
                    : new AuthorizationRequest(null, ctx.agentId(), ctx.grantKey(), ctx.prompt());
            grants.authorize(req, ctx.rootsOnGrant(), ctx.execRootsOnGrant());
            return PermissionDecision.allow("授权放行");
        } catch (PermissionDeniedException e) {
            return PermissionDecision.deny(e);
        }
    }
}