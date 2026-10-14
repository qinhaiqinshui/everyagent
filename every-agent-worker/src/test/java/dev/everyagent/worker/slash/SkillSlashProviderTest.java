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
import dev.everyagent.worker.skill.Skill;
import dev.everyagent.worker.skill.SkillCatalog;

/**
 * SkillSlashProvider 合并数据源测试:目录扫描(SkillCatalog,真相源——内置/插件物化包
 * 与用户自制 skill 一并计入)+ 插件 SPI 贡献(过渡期保留)合并后 `/` 菜单条目数量、
 * 顺序(目录 → SPI)、同 id 去重(目录优先)与 SPI 条目的「插件 · 」来源标记。
 *
 * <p>SkillCatalog 用 Mockito mock(不依赖磁盘与 classpath 物化);
 * SlashCommandRegistry / SkillContributorRegistry 用真实实例(构造零依赖)。
 */
class SkillSlashProviderTest {

    /** 构造一组目录扫描形态的 skill(按 id 升序,与 SkillCatalog.scan() 的确定序一致)。 */
    private static List<Skill> catalogSkills() {
        return List.of(
                new Skill("agent-dispatch", "子 Agent", "派发子 Agent",
                        "/skills/agent-dispatch/skill.md"),
                new Skill("code-review", "代码审查", "审查代码变更",
                        "/skills/code-review/skill.md"),
                new Skill("plan", "计划模式", "做计划", "/skills/plan/skill.md"),
                new Skill("skill-creator", "Skill 创建器", "创建新 skill",
                        "/skills/skill-creator/skill.md"));
    }

    private static SkillCatalog mockCatalog(List<Skill> skills) {
        SkillCatalog catalog = mock(SkillCatalog.class);
        when(catalog.scan()).thenReturn(skills);
        return catalog;
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

    private static SkillSlashProvider newProvider(SlashCommandRegistry registry, SkillCatalog catalog,
            SkillContributorRegistry contributors) {
        return new SkillSlashProvider(registry, catalog, contributors);
    }

    @Test
    void catalogSkillsAllBecomeMenuItems() {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        newProvider(registry, mockCatalog(catalogSkills()), new SkillContributorRegistry());

        List<SlashCommandItem> items = registry.list();
        assertEquals(4, items.size());
        assertEquals("skill:agent-dispatch", items.get(0).id());
        assertEquals("skill:code-review", items.get(1).id());
        assertEquals("skill:plan", items.get(2).id());
        assertEquals("skill:skill-creator", items.get(3).id());
    }

    @Test
    void emptyCatalogYieldsNoItems() {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        newProvider(registry, mockCatalog(List.of()), new SkillContributorRegistry());

        assertTrue(registry.list().isEmpty());
    }

    @Test
    void itemsAreInSkillsGroup() {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        newProvider(registry, mockCatalog(catalogSkills()), new SkillContributorRegistry());

        for (SlashCommandItem item : registry.list()) {
            assertEquals("Skills", item.group());
        }
    }

    // ---- 插件 SPI(SkillContributor)并入 / 菜单(过渡期保留的合并分支) ----

    @Test
    void pluginSkillsMergedAfterCatalogWithBadge() {
        SkillContributorRegistry contributors = registryOf(
                contributor("demo", pskill("demo-skill", "演示技能", "演示用途",
                        "/plugins/demo/skill.md")));
        SlashCommandRegistry registry = new SlashCommandRegistry();

        newProvider(registry, mockCatalog(catalogSkills()), contributors);

        List<SlashCommandItem> items = registry.list();
        assertEquals(5, items.size(), "目录 4 + 插件 1");
        assertEquals("skill:demo-skill", items.get(4).id(), "插件条目排在目录条目之后");
        assertEquals("演示技能", items.get(4).title());
        assertTrue(items.get(4).subtitle().startsWith("插件 · "), "副标题带来源标记: "
                + items.get(4).subtitle());
        assertEquals("插件 · 演示用途", items.get(4).subtitle());
        assertEquals("Skills", items.get(4).group(), "插件条目与目录条目同组");
        // opaque token 与目录条目同构:kind=system.skill,payload 携带 skillId/skillPath
        SlashTokenEncoder.ParsedToken token = SlashTokenEncoder.parseToken(items.get(4).insertText());
        assertEquals(SkillSlashProvider.SKILL_INPUT_TOKEN_KIND, token.kind());
        assertEquals("demo-skill", token.payload().path("skillId").asString());
        assertEquals("/plugins/demo/skill.md", token.payload().path("skillPath").asString());
    }

    @Test
    void pluginSkillBlankDescriptionKeepsBadgeOnly() {
        SkillContributorRegistry contributors = registryOf(
                contributor("demo", pskill("demo-blank", "无描述技能", " ", "/plugins/demo/skill.md")));
        SlashCommandRegistry registry = new SlashCommandRegistry();

        newProvider(registry, mockCatalog(List.of()), contributors);

        List<SlashCommandItem> items = registry.list();
        assertEquals(1, items.size());
        assertEquals("插件", items.get(0).subtitle(), "描述空缺时标记独自成副标题");
    }

    @Test
    void catalogWinsOverSpiSameId() {
        // 插件贡献与目录同 id(plan)→ 目录条目胜出;SPI 独有条目保留
        SkillContributorRegistry contributors = registryOf(
                contributor("demo",
                        pskill("plan", "插件计划", "不应出现", "/plugins/demo/plan.md"),
                        pskill("demo-skill", "SPI 技能", "SPI 声明保留", "/plugins/demo/skill.md")));
        SlashCommandRegistry registry = new SlashCommandRegistry();

        newProvider(registry, mockCatalog(catalogSkills()), contributors);

        List<SlashCommandItem> items = registry.list();
        assertEquals(5, items.size(), "目录 4 + SPI demo-skill,plan 不重复");
        SlashCommandItem plan = items.stream()
                .filter(i -> i.id().equals("skill:plan")).findFirst().orElseThrow();
        assertTrue(plan.subtitle() == null || !plan.subtitle().startsWith("插件 · "),
                "目录条目不被插件同名贡献覆盖: " + plan.subtitle());
        assertEquals("做计划", plan.subtitle());
    }

    @Test
    void noContributorsMenuIdenticalToCatalog() {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        newProvider(registry, mockCatalog(catalogSkills()), new SkillContributorRegistry());

        // 零回归:无插件贡献时条目序列与目录扫描完全一致,且不出现来源标记
        List<SlashCommandItem> items = registry.list();
        assertEquals(List.of("skill:agent-dispatch", "skill:code-review", "skill:plan",
                "skill:skill-creator"), items.stream().map(SlashCommandItem::id).toList());
        for (SlashCommandItem item : items) {
            assertTrue(item.subtitle() == null || !item.subtitle().startsWith("插件 · "),
                    "无插件贡献时不出现来源标记");
        }
    }
}
