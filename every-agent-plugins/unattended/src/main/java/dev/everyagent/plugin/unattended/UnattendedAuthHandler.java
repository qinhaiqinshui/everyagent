package dev.everyagent.plugin.unattended;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.permission.AuthorizationChain;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;

/**
 * 无人值守授权链节点:任务级开关开启时一律拒绝授权。
 *
 * <p>开关真相源是任务级 {@code unattended.mode} slash token(见
 * {@link UnattendedToken#KIND})——经 {@link WorkerServices#slashTokens()} 以磁盘为准查询
 * (运行中 / 终态 / 进程重启后一致),不再读任务 {@code metadata} 副本(非运行态写入会被
 * 静默丢弃,曾造成「胶囊已取消、开关仍生效」的双写不一致)。
 *
 * <p>命中开关即短路返回 {@code DENY};否则 {@code return next.proceed(req)}(等价不处理)。
 */
public class UnattendedAuthHandler implements AuthorizationHandler {

    private final WorkerServices services;

    public UnattendedAuthHandler(WorkerServices services) {
        this.services = services;
    }

    @Override
    public String id() { return "unattended-auth"; }

    @Override
    public float order() { return 200f; }

    @Override
    public AuthorizationDecision invoke(AuthorizationRequest req, AuthorizationChain next) throws Exception {
        if (services == null
                || !services.slashTokens().hasKind(req.context().subjectId(), UnattendedToken.KIND)) {
            return next.proceed(req);
        }
        return new AuthorizationDecision(AuthorizationDecision.Type.DENY, "无人值守模式拒绝授权");
    }
}
