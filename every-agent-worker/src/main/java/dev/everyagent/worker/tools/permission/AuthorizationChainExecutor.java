package dev.everyagent.worker.tools.permission;

import dev.everyagent.plugin.api.permission.AuthorizationChain;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationDecision;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 授权决议链组装器（filter 形态，与 TaskLifecycleExecutor 同构）。
 * <p>按 order 升序把 handler 折叠为嵌套链。组装器中立：链尾返回 {@code PASS} 只是
 * 「链走完、无人决策」的<b>信号</b>，不是放行结论——fail-closed 翻译（PASS → 默认拒绝）
 * 由调用方 {@link GrantRegistry} 收口（「无授权决议者，默认拒绝」，§7.8）。
 */
public final class AuthorizationChainExecutor {

    /**
     * 组装并执行授权决议链。
     * @param handlers handler 列表（将被稳定排序）
     * @param req 授权请求
     * @return 授权决议（链尾兜底 = PASS「无人决策」信号，由调用方按 fail-closed 拒绝）
     */
    public AuthorizationDecision run(List<AuthorizationHandler> handlers, AuthorizationRequest req) {
        List<AuthorizationHandler> sorted = new ArrayList<>(handlers);
        sorted.sort(Comparator.comparingDouble(AuthorizationHandler::order));

        // 链尾 = 无人决策信号(PASS);安全语义(默认拒绝)由调用方 GrantRegistry 翻译
        AuthorizationChain chain = r -> new AuthorizationDecision(AuthorizationDecision.Type.PASS, "chain tail");

        for (int i = sorted.size() - 1; i >= 0; i--) {
            AuthorizationHandler node = sorted.get(i);
            AuthorizationChain inner = chain;
            chain = r -> node.invoke(r, inner);
        }

        try {
            return chain.proceed(req);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }
}
