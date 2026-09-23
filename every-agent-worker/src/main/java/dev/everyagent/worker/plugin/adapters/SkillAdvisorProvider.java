package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.plugin.spi.AdvisorContext;
import dev.everyagent.worker.plugin.spi.AdvisorProvider;
import dev.everyagent.worker.skill.SkillAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * {@link SkillAdvisor} 适配器。
 *
 * <p>order = {@link Ordered#HIGHEST_PRECEDENCE} + 100，scope = MAIN（仅主 agent）。
 * SkillAdvisor 是共享无状态单例（@Bean），create() 返回同一实例。
 */
public class SkillAdvisorProvider implements AdvisorProvider {

    private final SkillAdvisor skillAdvisor;

    public SkillAdvisorProvider(SkillAdvisor skillAdvisor) {
        this.skillAdvisor = skillAdvisor;
    }

    @Override
    public String pluginId() {
        return "builtin.skill";
    }

    @Override
    public Scope scope() {
        return Scope.MAIN;
    }

    @Override
    public int order() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        return skillAdvisor;
    }
}
