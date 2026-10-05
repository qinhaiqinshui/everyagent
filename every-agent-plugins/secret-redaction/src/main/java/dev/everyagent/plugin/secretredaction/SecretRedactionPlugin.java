package dev.everyagent.plugin.secretredaction;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;

/**
 * 凭据输出脱敏插件入口。
 *
 * <p>只做一件事：注册 {@link SecretRedactionInterceptor}（工具执行拦截链上行段）。
 * 掩码规则源 {@link SecretRedactor} 完全住在插件内，删除本插件即删除全部脱敏概念与功能；
 * 核心不持有脱敏逻辑，也不依赖本插件。
 */
public class SecretRedactionPlugin implements EveryAgentPlugin {

    @Override
    public String id() {
        return "secret-redaction";
    }

    @Override
    public void activate(WorkerPluginContext ctx) {
        ctx.registerToolExecutionInterceptor(new SecretRedactionInterceptor());
    }
}
