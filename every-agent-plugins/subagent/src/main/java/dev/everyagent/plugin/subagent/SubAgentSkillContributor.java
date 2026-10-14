package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.skill.PluginSkill;
import dev.everyagent.plugin.api.skill.SkillContributor;
import dev.everyagent.plugin.api.config.WorkerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * 贡献「子 Agent」skill（agent-dispatch）。
 *
 * <p>从 worker BuiltInSkills 迁出。知识包 classpath:skill/agent-dispatch.md
 * 物化到系统技能目录(与 BuiltInSkills.materialize 同模式:内容逐字节一致则跳过)。
 */
public class SubAgentSkillContributor implements SkillContributor {

    private static final Logger log = LoggerFactory.getLogger(SubAgentSkillContributor.class);

    private final Path knowledgeRoot;
    private PluginSkill cachedSkill;

    public SubAgentSkillContributor(WorkerConfig props) {
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
            byte[] content = res.getContentAsByteArray();
            if (!sameContent(target, content)) {
                // 内容不一致(升级知识包,或知识包被误改)则覆盖;一致则跳过(幂等)
                Files.write(target, content);
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

    /** 目标文件内容是否已是给定字节(逐字节比较,与 BuiltInSkills.sameContent 同判据)。 */
    private static boolean sameContent(Path target, byte[] content) {
        try {
            return Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), content);
        } catch (IOException e) {
            return false; // 读不到按不一致处理,覆盖写失败会在上面的 catch 记录
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
