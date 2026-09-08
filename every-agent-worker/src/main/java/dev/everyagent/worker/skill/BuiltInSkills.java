package dev.everyagent.worker.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import dev.everyagent.worker.config.WorkerProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 内置 skill 装配(对应 nagent 的 {@code buildPluginSkills})。
 *
 * <p><b>渐进式披露</b>:本类不再把 {@code classpath:skill/<id>.md} 的完整方法论读进内存
 * 随 system prompt 注入;{@link Skill} 只声明 id/title/description 与知识包路径
 * (绝对路径,指向系统技能目录),正文由 {@link #materialize} 物化到
 * <b>系统技能目录</b> {@code <系统目录>/skills/}(§5.10/§13.8,不再拷入任务工作区 `.skills/`),
 * 由 AI 按需经 {@code read_file} 读取。默认全部激活——它们本就是
 * worker 子 Agent 工作流的方法论,无需用户开关。
 *
 * <p>toolIds 已对齐 worker 实际工具名:{@code run_agent}/{@code list_agents}/
 * {@code wait_agents}/{@code stop_agent}(nagent 原始为 {@code list_agent}/{@code wait_agent})。
 */
@Component
public class BuiltInSkills {

    private static final Logger log = LoggerFactory.getLogger(BuiltInSkills.class);

    /** 知识包在系统目录下的存放目录名(相对 homeDir)。 */
    public static final String KNOWLEDGE_DIR = "skills";

    /** classpath 知识包位置模板:{@code skill/<id>.md}(与 knowledgePath 文件名一致)。 */
    private static final String RESOURCE_PREFIX = "skill/";

    private final Path knowledgeRoot;
    private final List<Skill> skills;

    public BuiltInSkills(WorkerProperties props) {
        this.knowledgeRoot = props.resolveSkillsDir();
        this.skills = List.of(
                new Skill("agent-dispatch", "子 Agent",
                        "## 适用条件\n"+
"- 当某个任务可以并行执行来提高效率时，交给子 Agent 执行。\n"+
"- 当你只需要一个结果，但是探索这个结果会读取大量无用历史上下文时，可以派发子Agent来帮你探索并得出你要的结论。\n"+
"- 当需要等待、停止、重启子 Agent，或查看它们的运行状态与结果时，使用本技能。",
                        knowledgeRoot.resolve("agent-dispatch.md").toString(),
                        List.of("run_agent", "list_agents", "wait_agents", "stop_agent")),
                new Skill("plan", "计划模式",
                        "## 适用条件\n"+
"- 当任务复杂需拆步骤执行时，使用本技能。\n"+
"- 当用户要求做计划时，使用本技能。",
                        knowledgeRoot.resolve("plan.md").toString(),
                        List.of("run_agent", "list_agents", "wait_agents", "stop_agent")));
    }

    /** 启动时一次性物化(幂等;worker 升级知识包时按大小不一致覆盖)。 */
    @PostConstruct
    void init() {
        materialize();
    }

    /** 系统技能目录根(知识包存放位置)。 */
    public Path getKnowledgeRoot() {
        return knowledgeRoot;
    }

    /** 当前激活的 skill(默认全部)。后续若需可配置开关,在此过滤即可。 */
    public List<Skill> getActiveSkills() {
        return skills;
    }

    /**
     * 把内置知识包物化到系统技能目录(渐进式披露的「落盘」侧)。
     *
     * <p>将 {@code classpath:skill/<id>.md} 复制为 {@code <系统目录>/skills/<id>.md},
     * 使 AI 能按 {@link Skill#knowledgePath()} 直接 {@code read_file} 读取(系统技能目录
     * 经 PermissionGate READ 窄例外 + Sandbox 只读附加根对工具放行,物化是知识包可被
     * 工具读到的唯一通道)。幂等:目标已存在且大小一致则跳过;
     * 不一致(worker 升级知识包)则覆盖,保证与 worker 版本同步。失败仅记日志降级——
     * 提示词中的路径指引仍在,不阻断 worker 启动。
     */
    public void materialize() {
        for (Skill s : skills) {
            try {
                ClassPathResource res = new ClassPathResource(RESOURCE_PREFIX + s.id() + ".md");
                if (!res.exists()) {
                    log.warn("内置 skill 知识包缺失(classpath): {}", RESOURCE_PREFIX + s.id() + ".md");
                    continue;
                }
                Path target = knowledgeRoot.resolve(s.id() + ".md").normalize();
                if (!target.startsWith(knowledgeRoot)) {
                    // 防御:knowledgePath 被配置为越界路径时直接放弃,不得写出技能目录
                    log.warn("skill 知识包路径越界,已跳过: {}", target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                if (Files.isRegularFile(target) && Files.size(target) == res.contentLength()) {
                    continue; // 幂等:已存在且大小一致(内容即 worker 版本)
                }
                try (InputStream in = res.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                log.warn("内置 skill 知识包物化失败 skill={} target={}", s.id(), s.knowledgePath(), e);
            }
        }
    }
}
