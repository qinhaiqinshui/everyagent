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
 * SkillSlashProvider 合并数据源测试:验证内置 skill + 外部 skill 合并后
 * `/` 菜单条目数量与顺序(内置在前)。
 *
 * <p>BuiltInSkills 与 ExternalSkillScanner 用 Mockito mock(不依赖 Spring 容器
 * 与 classpath 物化);SlashCommandRegistry 用真实实例(构造零依赖)。
 */
class SkillSlashProviderTest {

    /** 构造一组模拟的内置 skill(agent-dispatch、plan)。 */
    private static List<Skill> builtinSkills() {
        return List.of(
                new Skill("agent-dispatch", "子 Agent", "派发子 Agent", "/skills/agent-dispatch/skill.md",
                        List.of("run_agent")),
                new Skill("plan", "计划模式", "做计划", "/skills/plan/skill.md", List.of()));
    }

    private static BuiltInSkills mockBuiltIn(List<Skill> skills) {
        BuiltInSkills bis = mock(BuiltInSkills.class);
        when(bis.getActiveSkills()).thenReturn(skills);
        return bis;
    }

    private static ExternalSkillScanner mockScanner(List<Skill> external) {
        ExternalSkillScanner scanner = mock(ExternalSkillScanner.class);
        when(scanner.scan()).thenReturn(external);
        return scanner;
    }

    @Test
    void onlyBuiltinYieldsTwoItems() {
        BuiltInSkills bis = mockBuiltIn(builtinSkills());
        ExternalSkillScanner scanner = mockScanner(List.of());
        SlashCommandRegistry registry = new SlashCommandRegistry();

        new SkillSlashProvider(registry, bis, scanner);

        List<SlashCommandItem> items = registry.list();
        assertEquals(2, items.size());
        assertEquals("skill:agent-dispatch", items.get(0).id());
        assertEquals("skill:plan", items.get(1).id());
    }

    @Test
    void builtinAndExternalMergedBuiltinFirst() {
        BuiltInSkills bis = mockBuiltIn(builtinSkills());
        List<Skill> external = List.of(
                new Skill("code-review", "代码审查", "审查代码变更", "/skills/code-review/skill.md", List.of()),
                new Skill("refactor", "重构", "重构建议", "/skills/refactor/skill.md", List.of()));
        ExternalSkillScanner scanner = mockScanner(external);
        SlashCommandRegistry registry = new SlashCommandRegistry();

        new SkillSlashProvider(registry, bis, scanner);

        List<SlashCommandItem> items = registry.list();
        // 内置 2 + 外部 2 = 4
        assertEquals(4, items.size());
        // 内置在前
        assertEquals("skill:agent-dispatch", items.get(0).id());
        assertEquals("skill:plan", items.get(1).id());
        // 外部在后(保持扫描顺序)
        assertEquals("skill:code-review", items.get(2).id());
        assertEquals("skill:refactor", items.get(3).id());
    }

    @Test
    void emptyExternalFallsBackToBuiltinOnly() {
        BuiltInSkills bis = mockBuiltIn(builtinSkills());
        ExternalSkillScanner scanner = mockScanner(List.of());
        SlashCommandRegistry registry = new SlashCommandRegistry();

        new SkillSlashProvider(registry, bis, scanner);

        assertEquals(2, registry.list().size());
    }

    @Test
    void noBuiltinOnlyExternal() {
        BuiltInSkills bis = mockBuiltIn(List.of());
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
        BuiltInSkills bis = mockBuiltIn(builtinSkills());
        ExternalSkillScanner scanner = mockScanner(List.of());
        SlashCommandRegistry registry = new SlashCommandRegistry();

        new SkillSlashProvider(registry, bis, scanner);

        for (SlashCommandItem item : registry.list()) {
            assertEquals("Skills", item.group());
        }
    }
}
