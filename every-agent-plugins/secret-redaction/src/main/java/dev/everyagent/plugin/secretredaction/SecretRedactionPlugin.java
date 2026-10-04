package dev.everyagent.plugin.secretredaction;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;

/**
 * 凭据输出脱敏插件入口。
 *
 * <p>只做一件事：注册 {@link SecretRedactionInterceptor}（工具执行拦截链上行段）。
 * 规则源在 {@code SecretPatterns}（plugin-api），与沙箱 env 继承闸门共用同一份定义。
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
