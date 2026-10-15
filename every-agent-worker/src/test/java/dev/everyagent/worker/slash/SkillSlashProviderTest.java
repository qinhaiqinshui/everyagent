package dev.everyagent.worker.slash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.SkillExposureStore;
import dev.everyagent.worker.skill.Skill;
import dev.everyagent.worker.skill.SkillCatalog;

/**
 * SkillSlashProvider 单源测试:数据源<b>唯一</b> = {@link SkillCatalog#scan()} 目录
 * 扫描,全量成为 `/` 菜单项(条目集合与顺序 = catalog 输出)。
 *
 * <p>两条关键钉子:
 * <ol>
 *   <li><b>菜单不受排除配置影响</b>——provider 构造签名只有
 *       {@code (SlashCommandRegistry, SkillCatalog)},不接 SkillExposureStore;
 *       即使存在「plan 在某工作区不自动暴露」的有效配置(真实 store 落盘),
 *       plan 仍在菜单里(排除只过滤 prompt 注入,不过滤菜单);</li>
 *   <li><b>无 SPI 来源前缀</b>——SPI 合并分支已删,副标题就是 description 原文,
 *       不出现「插件 · 」badge,条目数也不会超出 catalog。</li>
 * </ol>
 *
 * <p>SkillCatalog 用 Mockito mock(不依赖磁盘);排除配置用真实 SkillExposureStore
 * 落盘到临时 homeDir。
 */
class SkillSlashProviderTest {

    @TempDir
    Path tmp;

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

    /**
     * 真实 SkillExposureStore(落盘临时 homeDir)。{@code init()} 是 @PostConstruct
     * 的包内可见方法(在 modules 包),测试经反射以同款入口初始化。
     */
    private SkillExposureStore realStore(Path home) throws Exception {
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(home.toString());
        SkillExposureStore store = new SkillExposureStore(props);
        Method init = SkillExposureStore.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(store);
        return store;
    }

    @Test
    void catalogSkillsAllBecomeMenuItems() {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        new SkillSlashProvider(registry, mockCatalog(catalogSkills()));

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
        new SkillSlashProvider(registry, mockCatalog(List.of()));

        assertTrue(registry.list().isEmpty());
    }

    @Test
    void itemsAreInSkillsGroup() {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        new SkillSlashProvider(registry, mockCatalog(catalogSkills()));

        for (SlashCommandItem item : registry.list()) {
            assertEquals("Skills", item.group());
        }
    }

    // ---- 钉子 1:被排除(不进 prompt)的 skill 仍在 `/` 菜单里(菜单永远全量) ----

    @Test
    void excludedSkillStillAppearsInMenu() throws Exception {
        Path home = Files.createDirectory(tmp.resolve("home"));
        SkillExposureStore store = realStore(home);
        String wsRoot = Path.of("C:", "ws", "alpha").toAbsolutePath().normalize().toString();
        store.setConfig("plan", List.of(wsRoot), List.of());
        // 前置自检:排除配置真实存在且可读(prompt 注入侧会据此过滤 plan)
        assertFalse(store.isExposed("plan", wsRoot),
                "前置:plan 已配置在该工作区不自动暴露");

        // provider 构造签名 (registry, catalog) —— 不接 SkillExposureStore,
        // 菜单数据源与排除配置零关联:plan 仍在菜单(用户显式点名调用不被静默拦掉)
        SlashCommandRegistry registry = new SlashCommandRegistry();
        new SkillSlashProvider(registry, mockCatalog(catalogSkills()));

        List<String> ids = registry.list().stream().map(SlashCommandItem::id).toList();
        assertEquals(List.of("skill:agent-dispatch", "skill:code-review", "skill:plan",
                "skill:skill-creator"), ids, "菜单永远全量,不受排除配置影响");
    }

    // ---- 钉子 2:无 SPI 来源前缀(副标题 = description 原文,无「插件 · 」badge) ----

    @Test
    void noSpiSourcePrefixInSubtitles() {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        new SkillSlashProvider(registry, mockCatalog(catalogSkills()));

        List<SlashCommandItem> items = registry.list();
        assertEquals(4, items.size(), "条目数 = 目录扫描数,无 SPI 合并来源");
        for (SlashCommandItem item : items) {
            assertNotNull(item.subtitle(), "目录条目 description 非空,副标题非 null");
            assertTrue(!item.subtitle().startsWith("插件 · ") && !"插件".equals(item.subtitle()),
                    "不出现 SPI 来源前缀/badge: " + item.subtitle());
        }
        assertEquals("派发子 Agent", items.get(0).subtitle(), "副标题就是 description 原文");
        assertEquals("做计划", items.get(2).subtitle());
    }

    // ---- opaque token 结构(交互与选中执行路径) ----

    @Test
    void tokenPayloadCarriesSkillIdAndPath() {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        new SkillSlashProvider(registry, mockCatalog(catalogSkills()));

        SlashCommandItem plan = registry.list().stream()
                .filter(i -> i.id().equals("skill:plan")).findFirst().orElseThrow();
        SlashTokenEncoder.ParsedToken token = SlashTokenEncoder.parseToken(plan.insertText());
        assertEquals(SkillSlashProvider.SKILL_INPUT_TOKEN_KIND, token.kind());
        assertEquals("plan", token.payload().path("skillId").asString());
        assertEquals("计划模式", token.payload().path("skillTitle").asString());
        assertEquals("/skills/plan/skill.md", token.payload().path("skillPath").asString());
    }
}
