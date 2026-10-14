package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.worker.modules.SkillExclusionStore;
import dev.everyagent.worker.os.SandboxPathRegistry;
import dev.everyagent.worker.skill.SkillAdvisor;
import dev.everyagent.worker.skill.SkillCatalog;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * {@link SkillAdvisor} 适配器 —— <b>per-run 装配</b>。
 *
 * <p>provider 本身是持共享依赖({@link SkillCatalog} / {@link SkillExclusionStore} /
 * {@link SandboxPathRegistry})的无状态单例;{@link #create} 每次 new 一个携带本次
 * {@link AdvisorContext#workspaceRoot()} 的 {@link SkillAdvisor}——按工作区的排除
 * 过滤需要「当前 run 所属工作区根」,共享 advisor 单例拿不到;SkillAdvisor 无状态,
 * per-run 实例零成本。
 *
 * <p><b>不重写 {@code appliesTo}</b>(默认 true):子 agent 同规则注入 skill 索引,
 * 其 {@code workspaceRoot()} 即所属任务的工作区(主/子共用同一运行入口)。
 *
 * <p>order = {@link Ordered#HIGHEST_PRECEDENCE} + 100(高于 ToolCallingAdvisor 的
 * +300,skill 索引作为最外圈注入,对工具循环每一轮都生效)。
 */
public class SkillAdvisorProvider implements AdvisorProvider {

    private final SkillCatalog catalog;
    private final SkillExclusionStore exclusions;
    private final SandboxPathRegistry pathRegistry;

    public SkillAdvisorProvider(SkillCatalog catalog, SkillExclusionStore exclusions,
            SandboxPathRegistry pathRegistry) {
        this.catalog = catalog;
        this.exclusions = exclusions;
        this.pathRegistry = pathRegistry;
    }

    @Override
    public String pluginId() {
        return "builtin.skill";
    }

    @Override
    public int order() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        return new SkillAdvisor(catalog, exclusions, pathRegistry, ctx.workspaceRoot());
    }
}

