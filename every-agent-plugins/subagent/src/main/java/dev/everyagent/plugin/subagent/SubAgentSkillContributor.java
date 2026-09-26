package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.skill.PluginSkill;
import dev.everyagent.plugin.api.skill.SkillContributor;
import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 贡献「子 Agent」skill（agent-dispatch）。
 *
 * <p>从 worker BuiltInSkills 迁出。知识包 classpath:skill/agent-dispatch.md
 * 物化到系统技能目录（与 BuiltInSkills.materialize 同模式）。
 */
public class SubAgentSkillContributor implements SkillContributor {

    private static final Logger log = LoggerFactory.getLogger(SubAgentSkillContributor.class);

    private final Path knowledgeRoot;
    private PluginSkill cachedSkill;

    public SubAgentSkillContributor(WorkerProperties props) {
        this.knowledgeRoot = props.resolveSkillsDir();
    }

    void init() {
        materialize();
    }

    private void materialize() {
        try {
            ClassPathResource res = new ClassPathResource("skill/agent-dispatch.md");
            if (!res.exists()) {
                log.warn("subagent 插件 skill 知识包缺失(classpath): skill/agent-dispatch.md");
                return;
            }
            Path target = knowledgeRoot.resolve("agent-dispatch").resolve("skill.md").normalize();
            if (!target.startsWith(knowledgeRoot)) {
                log.warn("skill 知识包路径越界,已跳过: {}", target);
                return;
            }
            Files.createDirectories(target.getParent());
            if (Files.isRegularFile(target) && Files.size(target) == res.contentLength()) {
                // 幂等：已存在且大小一致
            } else {
                try (InputStream in = res.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            cachedSkill = new PluginSkill(
                    "agent-dispatch",
                    "子 Agent",
                    "## 适用条件\n" +
                            "- 当某个任务可以并行执行来提高效率时，交给子 Agent 执行。\n" +
                            "- 当你只需要一个结果，但是探索这个结果会读取大量无用历史上下文时，可以派发子Agent来帮你探索并得出你要的结论。\n" +
                            "- 当需要等待、停止、重启子 Agent，或查看它们的运行状态与结果时，使用本技能。",
                    target.toString(),
                    List.of("run_agent", "list_agents", "wait_agents", "stop_agent"));
        } catch (IOException e) {
            log.warn("subagent 插件 skill 知识包物化失败", e);
        }
    }

    @Override
    public String pluginId() {
        return "subagent";
    }

    @Override
    public List<PluginSkill> skills() {
        if (cachedSkill == null) {
            return List.of();
        }
        return List.of(cachedSkill);
    }
}
