package dev.everyagent.plugin.aireview;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.permission.AuthorizationChain;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;

/**
 * 「授权弹窗改 AI 审议」授权链节点。
 *
 * <p>开关真相源是<b>任务级 slash token</b>（{@link AiReviewToken#KIND} = {@code ai.review}）：
 * 经 {@link WorkerServices#slashTokens()} 按 {@code subjectId} 查询 token 是否存在来判定本任务
 * 是否开启 AI 审议。读取恒以磁盘 {@code slash-tokens.json} 为准（运行中 / 终态 / 进程重启后一致），
 * <b>不再读 {@code metadata["ai-review"]}</b>（那是随 meta.json 落盘的副本，任务不在内存时写入被
 * 静默丢弃，曾造成「胶囊已取消、开关仍生效」的双写不一致）。
 *
 * <p>未开启（无 token）→ 不适用，原样交下一环（{@code next.proceed(req)}）；开启 → 交
 * {@link AiAuthReviewer} 审议三态：ALLOW/DENY 短路，PASS（fallback / ESCALATE）交下一环。
 */
public class AiReviewAuthHandler implements AuthorizationHandler {
    private final AiAuthReviewer reviewer;
    private final WorkerServices services;

    public AiReviewAuthHandler(AiAuthReviewer reviewer, WorkerServices services) {
        this.reviewer = reviewer;
        this.services = services;
    }

    @Override
    public String id() { return "ai-review-auth"; }

    @Override
    public float order() { return 100f; }

    @Override
    public AuthorizationDecision invoke(AuthorizationRequest req, AuthorizationChain next) throws Exception {
        // 开关真相源：任务级 slash token（ai.review）；无 token → 不适用，交下一环
        if (!services.slashTokens().hasKind(req.context().subjectId(), AiReviewToken.KIND)) {
            return next.proceed(req); // 不适用，放行
        }
        ReviewDecision d = reviewer.review(req);
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
