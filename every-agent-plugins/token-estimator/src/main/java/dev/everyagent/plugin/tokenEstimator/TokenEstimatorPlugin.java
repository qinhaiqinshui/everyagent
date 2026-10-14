// ea: 插件入口类：activate() 里经 WorkerPluginContext 注册扩展点（本插件注册 TokenEstimator SPI 实现与校准 Advisor；注册方法目录见 docs/plugin-guide/backend/overview.md）
package dev.everyagent.plugin.tokenEstimator;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token 估算与校准 —— 后端插件入口类。
 *
 * <p>本类由 worker 以独立 {@code URLClassLoader} 加载（非 Spring 托管 bean），
 * 因此<b>禁用一切 Spring 注解</b>（@Component / @Autowired / @Resource 等都不生效）；
 * 需要的宿主依赖一律在 {@link #activate} 里经 {@code ctx.services()} 或
 * {@code ctx.getService(Class)} 手工获取。plugin.json 的 main 字段指向本类全限定名：
 * {@code dev.everyagent.plugin.tokenEstimator.TokenEstimatorPlugin}。
 */
public class TokenEstimatorPlugin implements EveryAgentPlugin {

    private static final Logger log = LoggerFactory.getLogger(TokenEstimatorPlugin.class);

    /** 插件 id，必须与 plugin.json 的 id 字段一致（仅允许小写字母 / 数字 / 连字符）。 */
    @Override
    public String id() {
        return "token-estimator";
    }

    /**
     * 激活：在这里注册扩展点。单个插件激活抛异常只影响自己，不影响其他插件加载。
     *
     * @param ctx 插件上下文（注册方法 + 只读服务；辅助方法还有 pluginId() / pluginDir() /
     *            services() / config() / getService(Class)）
     */
    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        log.info("[token-estimator] 已激活（{}）", ctx.pluginDir());

        // 本插件只注册两个扩展点(注册方法全量目录见 docs/plugin-guide/backend/overview.md
        // 与 every-agent-plugin-api 的 WorkerPluginContext,此处不逐一罗列):
        //   ① registerTokenEstimator —— Token 估算器 SPI 实现(全仓唯一,限流/压缩/护栏共用);
        //   ② registerAdvisorProvider —— Token 校准 Advisor(用真实 completionTokens 在线校准①)。
        // 两者共享同一 BuiltinTokenEstimator 实例(校准系数共享)。
        BuiltinTokenEstimator estimator = new BuiltinTokenEstimator(ctx.services().config());
        ctx.registerTokenEstimator(estimator);
        log.info("[token-estimator] 已注册 BuiltinTokenEstimator");

        ctx.registerAdvisorProvider(new TokenCalibrationAdvisor.Provider(estimator));
        log.info("[token-estimator] 已注册 TokenCalibrationAdvisor.Provider");
    }
}
