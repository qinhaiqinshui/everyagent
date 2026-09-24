package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.skill.PluginSkill;
import dev.everyagent.plugin.api.skill.SkillContributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SkillContributor SPI 注册表。
 *
 * <p>模式同 ToolProviderRegistry / AdvisorProviderRegistry：
 * CopyOnWriteArrayList + PluginStateStore 过滤。
 * 注册时机：内置插件在 Spring 启动时（@Component 构造器或 @PostConstruct）注册；
 * 外部插件在 activate() 时经 WorkerPluginContext.registerSkillContributor 注册。
 *
 * <p>SkillAdvisor 合并 BuiltInSkills.getActiveSkills() + 本注册表的 skills()，
 * 按 pluginId + skillId 去重（内置优先）。
 */
@Component
public class SkillContributorRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillContributorRegistry.class);

    private final List<SkillContributor> contributors = new CopyOnWriteArrayList<>();
    private final PluginStateStore stateStore;

    public SkillContributorRegistry(PluginStateStore stateStore) {
        this.stateStore = stateStore;
    }

    /** 注册一个 SkillContributor。 */
    public void register(SkillContributor contributor) {
        contributors.add(contributor);
    }

    /**
     * 获取全部有效贡献的 skill（经 PluginStateStore 过滤禁用插件的贡献）。
     */
    public List<PluginSkill> getSkills() {
        List<PluginSkill> out = new ArrayList<>();
        for (SkillContributor c : contributors) {
            if (stateStore.isDisabled(c.pluginId())) {
                continue;
            }
            out.addAll(c.skills());
        }
        return out;
    }
}
