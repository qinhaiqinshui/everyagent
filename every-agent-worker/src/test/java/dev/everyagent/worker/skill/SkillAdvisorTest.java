package dev.everyagent.worker.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.SkillExposureStore;
import dev.everyagent.worker.plugin.adapters.SkillAdvisorProvider;

/**
 * SkillAdvisor 单源 + 按工作区排除过滤测试。四条钉子:
 * <ol>
 *   <li>无排除配置时<b>全部 skill 进 prompt</b>(含外部/无 frontmatter 的
 *       用户自制 skill——默认全部暴露);</li>
 *   <li>给 {@code plan} 配置排除当前工作区 root 后 {@code plan} 消失而其余仍在
 *       (root 比对两边同款 normalize);</li>
 *   <li>{@code workspaceRoot} 为 null/空白时放行注入(宁多勿漏,即便存在排除配置);</li>
 *   <li>{@link SkillAdvisorProvider#create} 每次产出携带各自 root 的<b>独立实例</b>
 *       ——两个不同 root 的 ctx 互不串扰(A root 排除 plan、B root 不排除)。</li>
 * </ol>
 *
 * <p>catalog 用真实 {@link SkillCatalog} 实扫临时 skillsDir(内置两包 + 一个无
 * frontmatter 的外部 skill);排除配置用真实 {@link SkillExposureStore} 落盘;
 * pathRegistry 传 null(无映射时原样返回,DIRECT 场景同款语义)。
 */
class SkillAdvisorTest {

    @TempDir
    Path tmp;

    private Path skillsDir;
    private Path homeDir;

    @BeforeEach
    void setUp() throws Exception {
        skillsDir = Files.createDirectory(tmp.resolve("skills"));
        homeDir = Files.createDirectory(tmp.resolve("home"));
        writeSkill("plan",
                "---\nname: 计划模式\ndescription: 当任务复杂需拆步骤执行时使用。\n---\n\n# 计划模式\n正文");
        writeSkill("skill-creator",
                "---\nname: Skill 创建器\ndescription: 当用户要求创建一个新的 skill 时使用。\n---\n\n# Skill 创建器\n正文");
        // 用户自制外部 skill:无 frontmatter(迁移前形态)——description 回落正文首行
        writeSkill("md-to-pdf", "# md-to-pdf\n把对话导出为 PDF 文件。\n");
    }

    private void writeSkill(String id, String content) throws Exception {
        Path dir = Files.createDirectory(skillsDir.resolve(id));
        Files.writeString(dir.resolve("skill.md"), content);
    }

    private SkillCatalog catalog() {
        WorkerProperties props = new WorkerProperties();
        props.setSkillsDir(skillsDir.toString());
        return new SkillCatalog(props);
    }

    /**
     * 真实 SkillExposureStore(落盘临时 homeDir)。{@code init()} 是 @PostConstruct
     * 的包内可见方法(在 modules 包),测试经反射以同款入口初始化。
     */
    private SkillExposureStore store() throws Exception {
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(homeDir.toString());
        SkillExposureStore store = new SkillExposureStore(props);
        Method init = SkillExposureStore.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(store);
        return store;
    }

    private static ChatClientRequest request() {
        List<Message> instructions = new ArrayList<>();
        instructions.add(new SystemMessage("你是助手"));
        instructions.add(new UserMessage("你好"));
        return ChatClientRequest.builder().prompt(new Prompt(instructions, mock(ChatOptions.class))).build();
    }

    /** 取 before() 注入的「# 可用技能」system 文本;未注入时返回 null。 */
    private static String injectedSkillsText(SkillAdvisor advisor) {
        ChatClientRequest out = advisor.before(request(), mock(AdvisorChain.class));
        for (Message m : out.prompt().getInstructions()) {
            if (m instanceof SystemMessage sm && sm.getText() != null
                    && sm.getText().startsWith("# 可用技能")) {
                return sm.getText();
            }
        }
        return null;
    }

    /** 以条目 id 标记(如 "(`plan`)")断言注入/未注入——不受路径含 id 字符干扰。 */
    private static void assertInjected(String text, String id) {
        assertTrue(text.contains("(`" + id + "`)"), "skill 应注入 prompt: " + id);
    }

    private static void assertNotInjected(String text, String id) {
        assertFalse(text.contains("(`" + id + "`)"), "skill 不应出现在 prompt: " + id);
    }

    // ---- order:HP + 100 不变(高于 ToolCallingAdvisor 的 +300) ----

    @Test
    void orderStaysAtHighestPrecedencePlus100() throws Exception {
        SkillAdvisor advisor = new SkillAdvisor(catalog(), store(), null, null);
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 100, advisor.getOrder());
    }

    // ---- 钉子 1:无排除配置 = 全部注入(含外部/无 frontmatter skill) ----

    @Test
    void noExclusionConfigInjectsAllSkills() throws Exception {
        SkillAdvisor advisor = new SkillAdvisor(catalog(), store(), null,
                Path.of("C:", "ws", "alpha").toString());

        String text = injectedSkillsText(advisor);
        assertNotNull(text, "应注入「# 可用技能」区块");
        assertInjected(text, "plan");
        assertInjected(text, "skill-creator");
        assertInjected(text, "md-to-pdf");
        assertTrue(text.contains("把对话导出为 PDF 文件"),
                "外部无 frontmatter skill 的 description(正文首行回落)也一并注入");
    }

    // ---- 钉子 2:排除当前工作区 root 后该 skill 消失、其余仍在 ----

    @Test
    void excludedPlanVanishesOthersRemain() throws Exception {
        Path ws = Files.createDirectory(tmp.resolve("ws"));
        String storedRoot = ws.toAbsolutePath().normalize().toString();
        SkillExposureStore exclusions = store();
        // RPC 层(skill.exposure.set)落盘前已按 toAbsolutePath().normalize() 加工
        exclusions.setConfig("plan", List.of(storedRoot), List.of());

        // advisor 拿到的 root 是非 normalize 形态(尾分隔符)——过滤前须做同款 normalize
        SkillAdvisor advisor = new SkillAdvisor(catalog(), exclusions, null,
                ws + System.getProperty("file.separator"));

        String text = injectedSkillsText(advisor);
        assertNotNull(text);
        assertNotInjected(text, "plan");
        assertInjected(text, "skill-creator");
        assertInjected(text, "md-to-pdf");
    }

    // ---- 钉子 3:workspaceRoot 为 null/空白时放行注入(宁多勿漏) ----

    @Test
    void nullOrBlankRootInjectsAllWithoutFiltering() throws Exception {
        SkillExposureStore exclusions = store();
        // 即便存在排除配置(且任何 root 都可能被排除),root 缺失也不过滤
        exclusions.setConfig("plan", List.of("C:\\nonexistent-ws"), List.of());

        for (String root : new String[] { null, "  ", "" }) {
            SkillAdvisor advisor = new SkillAdvisor(catalog(), exclusions, null, root);
            String text = injectedSkillsText(advisor);
            assertNotNull(text, "root 缺失时仍应注入技能区块,root=" + root);
            assertInjected(text, "plan");
            assertInjected(text, "skill-creator");
            assertInjected(text, "md-to-pdf");
        }
    }

    // ---- 钉子 4:provider 每次 create() 产出携带各自 root 的独立实例 ----

    @Test
    void providerCreatesIndependentPerRootInstances() throws Exception {
        Path wsA = Files.createDirectory(tmp.resolve("ws-a"));
        Path wsB = Files.createDirectory(tmp.resolve("ws-b"));
        SkillExposureStore exclusions = store();
        exclusions.setConfig("plan",
                List.of(wsA.toAbsolutePath().normalize().toString()),
                List.of());

        SkillAdvisorProvider provider = new SkillAdvisorProvider(catalog(), exclusions, null);
        Advisor advisorA = provider.create(ctxWithRoot(wsA.toString()));
        Advisor advisorB = provider.create(ctxWithRoot(wsB.toString()));

        assertTrue(advisorA instanceof SkillAdvisor);
        assertNotSame(advisorA, advisorB, "每次 create() 产出独立 per-run 实例");

        String textA = injectedSkillsText((SkillAdvisor) advisorA);
        String textB = injectedSkillsText((SkillAdvisor) advisorB);
        assertNotInjected(textA, "plan"); // A 工作区排除了 plan → A 的注入无 plan
        assertInjected(textB, "plan");    // B 工作区未排除 → B 的注入有 plan
        assertInjected(textA, "skill-creator");
        assertInjected(textB, "skill-creator");
    }

    private static AdvisorContext ctxWithRoot(String root) {
        AdvisorContext ctx = mock(AdvisorContext.class);
        when(ctx.workspaceRoot()).thenReturn(root);
        return ctx;
    }
}
