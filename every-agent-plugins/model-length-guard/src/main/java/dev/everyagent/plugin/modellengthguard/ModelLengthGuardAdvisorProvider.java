package dev.everyagent.plugin.modellengthguard;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.worker.agent.AgentEntity;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * {@link ModelLengthGuardAdvisor} 适配器。
 *
 * <p>order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 300（瞬时错误重试内侧、上下文压缩外侧）。
 * 每 run 新建实例。
 *
 * <p><b>TokenEstimator 延迟解析</b>:不在构造时固化引用,而是在 {@link #create(AdvisorContext)}
 * 时从 {@link WorkerServices#tokenEstimator()} 获取当前生效的估算器。WorkerServicesImpl 内部
 * 使用 AtomicReference,model-rate-limit 插件 registerTokenEstimator 替换后自动跟随。
 */
public class ModelLengthGuardAdvisorProvider implements AdvisorProvider {

    private final WorkerProperties props;
    private final WorkerServices services;

    public ModelLengthGuardAdvisorProvider(WorkerProperties props, WorkerServices services) {
        this.props = props;
        this.services = services; // 延迟解析的关键:不存 TokenEstimator 引用,存 WorkerServices
    }

    @Override
    public String pluginId() {
        return "builtin.model-length-guard";
    }

    @Override
    public int order() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 300;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = ((AdvisorContextImpl) ctx).agentEntity();
        // 延迟解析:每次 create 时从 WorkerServices 取当前生效的 TokenEstimator
        TokenEstimator estimator = services.tokenEstimator();
        return new ModelLengthGuardAdvisor(a, props, estimator);
    }
}
