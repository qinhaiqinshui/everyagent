package dev.everyagent.worker.slash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import dev.everyagent.plugin.api.skill.PluginSkill;
import dev.everyagent.plugin.api.skill.SkillContributor;
import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.everyagent.worker.plugin.registry.SkillContributorRegistry;
import dev.everyagent.worker.skill.BuiltInSkills;
import dev.everyagent.worker.skill.ExternalSkillScanner;
import dev.everyagent.worker.skill.Skill;

/**
 * SkillSlashProvider 合并数据源测试:验证内置 skill(主动+被动) + 插件 SPI 贡献 +
 * 外部 skill 三路合并后 `/` 菜单条目数量、顺序(内置 → 插件 → 外部)、同 id 去重
 * 优先级(内置 > 插件 SPI > 外部)与插件条目的「插件 · 」来源标记。
 *
 * <p>BuiltInSkills 与 ExternalSkillScanner 用 Mockito mock(不依赖 Spring 容器
 * 与 classpath 物化);SlashCommandRegistry / SkillContributorRegistry 用真实实例
 * (构造零依赖)。
 */
class SkillSlashProviderTest {

    /** 构造一组模拟的主动内置 skill(agent-dispatch、plan)。 */
    private static List<Skill> activeBuiltinSkills() {
        return List.of(
                new Skill("agent-dispatch", "子 Agent", "派发子 Agent", "/skills/agent-dispatch/skill.md",
                        List.of("run_agent")),
                new Skill("plan", "计划模式", "做计划", "/skills/plan/skill.md", List.of()));
    }

    /** 构造一个模拟的被动内置 skill(skill-creator)。 */
    private static List<Skill> passiveBuiltinSkills() {
        return List.of(
                new Skill("skill-creator", "Skill 创建器", "创建新 skill",
                        "/skills/skill-creator/skill.md", List.of()));
    }

    /** 全部内置(主动+被动),与 BuiltInSkills.getAllSkills() 行为一致。 */
    private static List<Skill> allBuiltinSkills() {
        return List.of(
                new Skill("agent-dispatch", "子 Agent", "派发子 Agent", "/skills/agent-dispatch/skill.md",
                        List.of("run_agent")),
                new Skill("plan", "计划模式", "做计划", "/skills/plan/skill.md", List.of()),
                new Skill("skill-creator", "Skill 创建器", "创建新 skill",
                        "/skills/skill-creator/skill.md", List.of()));
    }

    private static BuiltInSkills mockBuiltIn() {
        BuiltInSkills bis = mock(BuiltInSkills.class);
        when(bis.getActiveSkills()).thenReturn(activeBuiltinSkills());
        when(bis.getPassiveSkills()).thenReturn(passiveBuiltinSkills());
        when(bis.getAllSkills()).thenReturn(allBuiltinSkills());
        return bis;
    }

    private static ExternalSkillScanner mockScanner(List<Skill> external) {
        ExternalSkillScanner scanner = mock(ExternalSkillScanner.class);
        when(scanner.scan()).thenReturn(external);
        return scanner;
    }

    /** 桩 SkillContributor:固定返回一组 PluginSkill。 */
    private static SkillContributor contributor(String pluginId, PluginSkill... skills) {
        return new SkillContributor() {
            @Override
            public String pluginId() {
                return pluginId;
            }

            @Override
            public List<PluginSkill> skills() {
                return List.of(skills);
            }
        };
    }

    private static PluginSkill pskill(String id, String title, String description, String knowledgePath) {
        return new PluginSkill(id, title, description, knowledgePath, List.of());
    }

    /** 注册贡献者并返回注册表(链式便捷)。 */
    private static SkillContributorRegistry registryOf(SkillContributor... contributors) {
        SkillContributorRegistry registry = new SkillContributorRegistry();
        for (SkillContributor c : contributors) {
            registry.register(c);
        }
        return registry;
    }

    private static SkillSlashProvider newProvider(SlashCommandRegistry registry, BuiltInSkills bis,
            SkillContributorRegistry contributors, ExternalSkillScanner scanner) {
        return new SkillSlashProvider(registry, bis, contributors, scanner);
    }

    @Test
    void onlyBuiltinYieldsThreeItems() {
        BuiltInSkills bis = mockBuiltIn();
        ExternalSkillScanner scanner = mockScanner(List.of());
        SlashCommandRegistry registry = new SlashCommandRegistry();

        newProvider(registry, bis, new SkillContributorRegistry(), scanner);

        List<SlashCommandItem> items = registry.list();
        assertEquals(3, items.size());
        assertEquals("skill:agent-dispatch", items.get(0).id());
        assertEquals("skill:plan", items.get(1).id());
        assertEquals("skill:skill-creator", items.get(2).id());
    }

    @Test
    void builtinAndExternalMergedBuiltinFirst() {
        BuiltInSkills bis = mockBuiltIn();
        List<Skill> external = List.of(
                new Skill("code-review", "代码审查", "审查代码变更", "/skills/code-review/skill.md", List.of()),
                new Skill("refactor", "重构", "重构建议", "/skills/refactor/skill.md", List.of()));
        ExternalSkillScanner scanner = mockScanner(external);
        SlashCommandRegistry registry = new SlashCommandRegistry();

        newProvider(registry, bis, new SkillContributorRegistry(), scanner);

        List<SlashCommandItem> items = registry.list();
        // 内置 3(含被动) + 外部 2 = 5
        assertEquals(5, items.size());
        // 内置在前(主动 + 被动)
        assertEquals("skill:agent-dispatch", items.get(0).id());
        assertEquals("skill:plan", items.get(1).id());
        assertEquals("skill:skill-creator", items.get(2).id());
        // 外部在后(保持扫描顺序)
        assertEquals("skill:code-review", items.get(3).id());
        assertEquals("skill:refactor", items.get(4).id());
    }

    @Test
    void emptyExternalFallsBackToBuiltinOnly() {
        BuiltInSkills bis = mockBuiltIn();
        ExternalSkillScanner scanner = mockScanner(List.of());
        SlashCommandRegistry registry = new SlashCommandRegistry();

        newProvider(registry, bis, new SkillContributorRegistry(), scanner);

        assertEquals(3, registry.list().size());
    }

    @Test
    void noBuiltinOnlyExternal() {
        BuiltInSkills bis = mock(BuiltInSkills.class);
        when(bis.getAllSkills()).thenReturn(List.of());
        List<Skill> external = List.of(
                new Skill("only-external", "仅外部", "外部 skill", "/skills/only-external/skill.md", List.of()));
        ExternalSkillScanner scanner = mockScanner(external);
        SlashCommandRegistry registry = new SlashCommandRegistry();

        newProvider(registry, bis, new SkillContributorRegistry(), scanner);

        List<SlashCommandItem> items = registry.list();
        assertEquals(1, items.size());
        assertEquals("skill:only-external", items.get(0).id());
    }

    @Test
    void itemsAreInSkillsGroup() {
        BuiltInSkills bis = mockBuiltIn();
        ExternalSkillScanner scanner = mockScanner(List.of());
        SlashCommandRegistry registry = new SlashCommandRegistry();

        newProvider(registry, bis, new SkillContributorRegistry(), scanner);

        for (SlashCommandItem item : registry.list()) {
            assertEquals("Skills", item.group());
        }
    }

    // ---- 插件 SPI(SkillContributor)并入 / 菜单 ----

    @Test
    void pluginSkillsMergedAfterBuiltinWithBadge() {
        BuiltInSkills bis = mockBuiltIn();
        ExternalSkillScanner scanner = mockScanner(List.of());
        SkillContributorRegistry contributors = registryOf(
                contributor("demo", pskill("demo-skill", "演示技能", "演示用途",
                        "/plugins/demo/skill.md")));
        SlashCommandRegistry registry = new SlashCommandRegistry();

        newProvider(registry, bis, contributors, scanner);

        List<SlashCommandItem> items = registry.list();
        assertEquals(4, items.size(), "内置 3 + 插件 1");
        assertEquals("skill:demo-skill", items.get(3).id(), "插件条目排在内置之后");
        assertEquals("演示技能", items.get(3).title());
        assertTrue(items.get(3).subtitle().startsWith("插件 · "), "副标题带来源标记: "
                + items.get(3).subtitle());
        assertEquals("插件 · 演示用途", items.get(3).subtitle());
        assertEquals("Skills", items.get(3).group(), "插件条目与内置同组");
        // opaque token 与内置同构:kind=system.skill,payload 携带 skillId/skillPath
        SlashTokenEncoder.ParsedToken token = SlashTokenEncoder.parseToken(items.get(3).insertText());
        assertEquals(SkillSlashProvider.SKILL_INPUT_TOKEN_KIND, token.kind());
        assertEquals("demo-skill", token.payload().path("skillId").asString());
        assertEquals("/plugins/demo/skill.md", token.payload().path("skillPath").asString());
    }

    @Test
    void pluginSkillBlankDescriptionKeepsBadgeOnly() {
        BuiltInSkills bis = mock(BuiltInSkills.class);
        when(bis.getAllSkills()).thenReturn(List.of());
        ExternalSkillScanner scanner = mockScanner(List.of());
        SkillContributorRegistry contributors = registryOf(
                contributor("demo", pskill("demo-blank", "无描述技能", " ", "/plugins/demo/skill.md")));
        SlashCommandRegistry registry = new SlashCommandRegistry();

        newProvider(registry, bis, contributors, scanner);

        List<SlashCommandItem> items = registry.list();
        assertEquals(1, items.size());
        assertEquals("插件", items.get(0).subtitle(), "描述空缺时标记独自成副标题");
    }

    @Test
    void pluginSkillDedupBuiltinWinsAndBeatsExternal() {
        BuiltInSkills bis = mockBuiltIn();
        // 插件贡献与内置同 id(plan)→ 内置赢;插件 spi-skill 同时被外部扫描捞到 → SPI 赢
        ExternalSkillScanner scanner = mockScanner(List.of(
                new Skill("spi-skill", "spi-skill", "外部扫描的同名条目",
                        "/skills/spi-skill/skill.md", List.of()),
                new Skill("keep-external", "保留的外部", "未被插件覆盖", "/skills/keep/skill.md", List.of())));
        SkillContributorRegistry contributors = registryOf(
                contributor("demo",
                        pskill("plan", "插件计划", "不应出现", "/plugins/demo/plan.md"),
                        pskill("spi-skill", "SPI 技能", "SPI 声明优先",
                                "/plugins/demo/spi-skill.md")));
        SlashCommandRegistry registry = new SlashCommandRegistry();

        newProvider(registry, bis, contributors, scanner);

        List<SlashCommandItem> items = registry.list();
        assertEquals(5, items.size(), "内置 3 + SPI spi-skill + 外部 keep-external,plan/spi-skill 不重复");
        assertEquals("skill:plan", items.get(1).id());
        assertTrue(!items.get(1).subtitle().startsWith("插件 · "), "内置 plan 不被插件同名贡献覆盖");
        SlashCommandItem spiSkill = items.stream()
                .filter(i -> i.id().equals("skill:spi-skill")).findFirst().orElseThrow();
        assertTrue(spiSkill.subtitle().startsWith("插件 · "), "SPI 条目胜过外部扫描同名条目");
        assertEquals("skill:keep-external", items.get(4).id(), "未冲突的外部条目保留");
    }

    @Test
    void noContributorsMenuIdenticalToBefore() {
        BuiltInSkills bis = mockBuiltIn();
        List<Skill> external = List.of(
                new Skill("code-review", "代码审查", "审查代码变更", "/skills/code-review/skill.md", List.of()));
        ExternalSkillScanner scanner = mockScanner(external);
        SlashCommandRegistry registry = new SlashCommandRegistry();

        newProvider(registry, bis, new SkillContributorRegistry(), scanner);

        // 零回归:无插件贡献时条目序列与三路合并前(内置+外部)完全一致
        List<SlashCommandItem> items = registry.list();
        assertEquals(List.of("skill:agent-dispatch", "skill:plan", "skill:skill-creator",
                "skill:code-review"), items.stream().map(SlashCommandItem::id).toList());
        for (SlashCommandItem item : items) {
            assertTrue(item.subtitle() == null || !item.subtitle().startsWith("插件 · "),
                    "无插件贡献时不出现来源标记");
        }
    }
}
