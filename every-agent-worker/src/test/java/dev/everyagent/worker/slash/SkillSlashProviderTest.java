package dev.everyagent.worker.slash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.everyagent.worker.skill.BuiltInSkills;
import dev.everyagent.worker.skill.ExternalSkillScanner;
import dev.everyagent.worker.skill.Skill;

/**
 * SkillSlashProvider 合并数据源测试:验证内置 skill(主动+被动) + 外部 skill
 * 合并后 `/` 菜单条目数量与顺序(内置在前)。
 *
 * <p>BuiltInSkills 与 ExternalSkillScanner 用 Mockito mock(不依赖 Spring 容器
 * 与 classpath 物化);SlashCommandRegistry 用真实实例(构造零依赖)。
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

    @Test
    void onlyBuiltinYieldsThreeItems() {
        BuiltInSkills bis = mockBuiltIn();
        ExternalSkillScanner scanner = mockScanner(List.of());
        SlashCommandRegistry registry = new SlashCommandRegistry();

        new SkillSlashProvider(registry, bis, scanner);

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

        new SkillSlashProvider(registry, bis, scanner);

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

        new SkillSlashProvider(registry, bis, scanner);

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

        new SkillSlashProvider(registry, bis, scanner);

        List<SlashCommandItem> items = registry.list();
        assertEquals(1, items.size());
        assertEquals("skill:only-external", items.get(0).id());
    }

    @Test
    void itemsAreInSkillsGroup() {
        BuiltInSkills bis = mockBuiltIn();
        ExternalSkillScanner scanner = mockScanner(List.of());
        SlashCommandRegistry registry = new SlashCommandRegistry();

        new SkillSlashProvider(registry, bis, scanner);

        for (SlashCommandItem item : registry.list()) {
            assertEquals("Skills", item.group());
        }
    }
}
