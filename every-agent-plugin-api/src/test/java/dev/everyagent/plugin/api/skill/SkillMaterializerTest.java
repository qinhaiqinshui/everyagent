package dev.everyagent.plugin.api.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillMaterializer}:物化语义(幂等/等长覆盖/复原/残留清理/id 防御)与 classpath 发现
 * (目录形态 / jar 形态 / 父委派隔离)。
 *
 * <p>fixture 全部用临时目录 + 临时 jar + {@link URLClassLoader} 构造,不依赖任何插件模块或
 * worker 模块(api 模块不能依赖它们)。
 */
class SkillMaterializerTest {

    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    @TempDir
    Path temp;

    @BeforeAll
    static void disableJarUrlCaching() {
        // Windows 钉子:jar: URL 的 JarURLConnection 默认经全局 JarFileFactory 缓存 JarFile,
        // 流关闭后文件句柄仍被缓存持有,@TempDir 清理阶段删不掉临时 jar(JUnit 报
        // "Failed to close extension context")。关闭 jar 协议的连接缓存后,流关闭即释放句柄。
        URLConnection.setDefaultUseCaches("jar", false);
    }

    // ---- 物化语义 ----

    /** 1. 幂等:内容一致零写入(返回 false 且 mtime 不变,即未重写)。 */
    @Test
    void idempotentWhenContentIdentical() throws Exception {
        Path skills = temp.resolve("skills");
        byte[] content = "# 计划模式\n正文".getBytes(UTF_8);
        assertTrue(SkillMaterializer.materialize(skills, "plan", content), "首次物化必须写入");
        Path file = skills.resolve("plan/skill.md");
        FileTime mtime = Files.getLastModifiedTime(file);
        Thread.sleep(150); // mtime 粒度余量:若发生重写,时间戳必然前进
        assertFalse(SkillMaterializer.materialize(skills, "plan", content), "内容一致必须返回 false");
        assertEquals(mtime, Files.getLastModifiedTime(file), "零写入 = 文件未被重写(mtime 不变)");
        try (var stream = Files.list(file.getParent())) {
            assertEquals(List.of(file), stream.toList(), "不得留下任何 tmp 残留");
        }
    }

    /** 2. 等长内容改写必覆盖(回归:旧缺陷曾按文件大小判幂等,等长升级被静默跳过)。 */
    @Test
    void equalLengthChangeMustOverwrite() throws Exception {
        Path skills = temp.resolve("skills");
        byte[] oldBytes = "A".repeat(64).getBytes(UTF_8);
        byte[] newBytes = "B".repeat(64).getBytes(UTF_8);
        assertEquals(oldBytes.length, newBytes.length);
        assertTrue(SkillMaterializer.materialize(skills, "plan", oldBytes));
        assertTrue(SkillMaterializer.materialize(skills, "plan", newBytes),
                "内容变、大小不变:必须覆盖");
        assertArrayEquals(newBytes, Files.readAllBytes(skills.resolve("plan/skill.md")));
    }

    /** 3. 被删包可复原:物化 → 连目录删掉 → 再物化 → 内容一致。 */
    @Test
    void recoversDeletedPack() throws Exception {
        Path skills = temp.resolve("skills");
        byte[] content = "recoverable".getBytes(UTF_8);
        assertTrue(SkillMaterializer.materialize(skills, "plan", content));
        Path file = skills.resolve("plan/skill.md");
        Files.delete(file);
        Files.delete(file.getParent()); // 连目录一起删,顺带钉住「目录缺失时 createDirectories」
        assertTrue(SkillMaterializer.materialize(skills, "plan", content));
        assertArrayEquals(content, Files.readAllBytes(file));
    }

    /** 4. 非法 id(大写/下划线/`..`/空/`a/b` 等)与路径逃逸形态一律 IllegalArgumentException,且不做任何 IO。 */
    @Test
    void rejectsInvalidIdsAndPathTraversal() throws Exception {
        String[] bad = {"Plan", "PLAN", "plan_1", "plan.md", "..", ".", "", " ", "a/b", "a\\b",
                "-plan", "计划", "plan ", null};
        for (String id : bad) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SkillMaterializer.skillFile(temp.resolve("skills"), id),
                    "id=" + id);
            assertNotNull(ex.getMessage());
        }
        // materialize 同样在落盘前拒绝,且不产生任何文件
        assertThrows(IllegalArgumentException.class,
                () -> SkillMaterializer.materialize(temp.resolve("skills"), "..", new byte[]{1}));
        assertFalse(Files.exists(temp.resolve("skills")));

        for (String ok : new String[]{"plan", "skill-creator", "agent-dispatch", "0x", "a", "1-2"}) {
            assertTrue(SkillMaterializer.isValidId(ok), "应合法: " + ok);
        }
        for (String no : new String[]{null, "", "A", "_", "a_b", "..", "a/b", "a.b"}) {
            assertFalse(SkillMaterializer.isValidId(no), "应非法: " + no);
        }
    }

    /** 5. 旧扁平 {@code <id>.md}(skillsDir 直接下的同名 .md)被清理——含幂等零写入路径(清理先于幂等比较)。 */
    @Test
    void cleansLegacyFlatResidue() throws Exception {
        Path skills = temp.resolve("skills");
        Files.createDirectories(skills);
        Path flat = skills.resolve("plan.md");
        Files.writeString(flat, "legacy flat layout", UTF_8);
        byte[] content = "new layout".getBytes(UTF_8);

        assertTrue(SkillMaterializer.materialize(skills, "plan", content));
        assertFalse(Files.exists(flat), "旧扁平 <id>.md 残留必须被清理");
        assertTrue(Files.exists(skills.resolve("plan/skill.md")));

        Files.writeString(flat, "legacy flat layout again", UTF_8);
        assertFalse(SkillMaterializer.materialize(skills, "plan", content), "内容未变,幂等");
        assertFalse(Files.exists(flat), "幂等(零写入)调用也要清掉旧扁平残留");

        // 其他 id 的扁平文件不受影响
        Files.writeString(skills.resolve("other.md"), "keep", UTF_8);
        SkillMaterializer.materialize(skills, "plan", content);
        assertTrue(Files.exists(skills.resolve("other.md")), "只清理同名 <id>.md,不动其他文件");
    }

    /** 单资源物化:读 classpath + 幂等 + 资源缺失抛 IOException。 */
    @Test
    void materializeFromClasspathReadsResourceAndMissingThrows() throws Exception {
        Path cpRoot = Files.createDirectories(temp.resolve("cp"));
        write(cpRoot.resolve("skill/plan/skill.md"), "from cp".getBytes(UTF_8));
        try (URLClassLoader cl = new URLClassLoader(new URL[]{cpRoot.toUri().toURL()}, null)) {
            Path skills = temp.resolve("skills");
            assertTrue(SkillMaterializer.materializeFromClasspath(
                    skills, "plan", cl, "skill/plan/skill.md"));
            assertFalse(SkillMaterializer.materializeFromClasspath(
                    skills, "plan", cl, "skill/plan/skill.md"), "第二次幂等");
            assertEquals("from cp", Files.readString(skills.resolve("plan/skill.md"), UTF_8));
            assertThrows(IOException.class, () -> SkillMaterializer.materializeFromClasspath(
                    skills, "plan", cl, "skill/absent/skill.md"), "资源缺失须抛 IOException");
        }
    }

    // ---- classpath 发现 ----

    /** 6 + 9. 目录形态 classpath:发现正确 id 集合、按 id 排序、拒绝深嵌套非法布局、重复调用幂等。 */
    @Test
    void discoversDirectoryClasspathPacksSorted() throws Exception {
        Path cpRoot = Files.createDirectories(temp.resolve("cp"));
        // 刻意乱序创建,钉住「返回按 id 排序」
        write(cpRoot.resolve("skill/zeta/skill.md"), "zeta body".getBytes(UTF_8));
        write(cpRoot.resolve("skill/alpha/skill.md"), "alpha body".getBytes(UTF_8));
        write(cpRoot.resolve("skill/mid/skill.md"), "mid body".getBytes(UTF_8));
        // 深嵌套非法布局:一级 * 不得接受(否则会从 skill/nested/deep/skill.md 猜出无意义 id)
        write(cpRoot.resolve("skill/nested/deep/skill.md"), "bad layout".getBytes(UTF_8));

        try (URLClassLoader cl = new URLClassLoader(new URL[]{cpRoot.toUri().toURL()}, null)) {
            Path skills = temp.resolve("skills");
            List<String> ids = SkillMaterializer.materializeAll(skills, cl, "skill");
            assertEquals(List.of("alpha", "mid", "zeta"), ids);
            assertEquals("alpha body", Files.readString(skills.resolve("alpha/skill.md"), UTF_8));
            assertEquals("mid body", Files.readString(skills.resolve("mid/skill.md"), UTF_8));
            assertEquals("zeta body", Files.readString(skills.resolve("zeta/skill.md"), UTF_8));
            assertFalse(Files.exists(skills.resolve("nested")), "深嵌套布局不得被物化");

            // 再次物化:磁盘已逐字节一致,但仍返回同一 id 清单(「已安装」与是否写入无关)
            assertEquals(ids, SkillMaterializer.materializeAll(skills, cl, "skill"));

            // 默认根 = PLUGIN_SKILL_ROOT
            assertEquals(ids, SkillMaterializer.materializeAllFromClasspath(temp.resolve("skills2"), cl));
        }
    }

    /** 7. jar 形态 classpath:同样发现正确 id 集合(按 id 排序)。 */
    @Test
    void discoversJarClasspathPacks() throws Exception {
        Path jar = buildJar(temp.resolve("plugin.jar"), Map.of(
                "skill/gamma/skill.md", "gamma body".getBytes(UTF_8),
                "skill/delta/skill.md", "delta body".getBytes(UTF_8)));
        try (URLClassLoader cl = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            Path skills = temp.resolve("skills");
            List<String> ids = SkillMaterializer.materializeAll(skills, cl, "skill");
            assertEquals(List.of("delta", "gamma"), ids);
            assertEquals("delta body", Files.readString(skills.resolve("delta/skill.md"), UTF_8));
            assertEquals("gamma body", Files.readString(skills.resolve("gamma/skill.md"), UTF_8));
        }
    }

    /**
     * 8. 父委派隔离钉子(回归护栏,最重要):复现 worker 侧 PluginLoader 的 loader 策略——
     * parent loader 的 classpath 含 {@code builtin-skill/plan} 与 {@code builtin-skill/skill-creator}
     * (模拟 worker 资源),child loader = 含 {@code skill/agent-dispatch} 的插件 jar。
     * 断言:插件视角扫 {@code skill/} 只看到自己的包;反向 worker 视角扫 {@code builtin-skill/}
     * 只有自己的包。第三条断言证明父委派链路真实存在(child 能经 parent 看到 builtin-skill 根),
     * 即前两条隔离靠的是「根名不同」,而非 fixture 失效。
     */
    @Test
    void parentDelegationRootIsolation() throws Exception {
        Path workerCp = Files.createDirectories(temp.resolve("worker-cp"));
        write(workerCp.resolve("builtin-skill/plan/skill.md"), "worker builtin plan pack".getBytes(UTF_8));
        write(workerCp.resolve("builtin-skill/skill-creator/skill.md"),
                "worker builtin skill-creator pack".getBytes(UTF_8));
        Path pluginJar = buildJar(temp.resolve("plugin.jar"), Map.of(
                "skill/agent-dispatch/skill.md", "plugin agent-dispatch pack".getBytes(UTF_8)));

        try (URLClassLoader parent = new URLClassLoader(new URL[]{workerCp.toUri().toURL()}, null);
             URLClassLoader child = new URLClassLoader(new URL[]{pluginJar.toUri().toURL()}, parent)) {

            // 插件视角:只物化自己的包,看不到 worker 的内置包(它们不在 skill/ 根下)
            Path skillsChild = temp.resolve("skills-child");
            assertEquals(List.of("agent-dispatch"),
                    SkillMaterializer.materializeAll(skillsChild, child, SkillMaterializer.PLUGIN_SKILL_ROOT));
            assertFalse(Files.exists(skillsChild.resolve("plan/skill.md")), "不得代装 worker 的 plan");
            assertFalse(Files.exists(skillsChild.resolve("skill-creator/skill.md")));
            assertEquals("plugin agent-dispatch pack",
                    Files.readString(skillsChild.resolve("agent-dispatch/skill.md"), UTF_8));

            // 反向:worker 视角扫自己的 builtin-skill/ 根,只有自己的两个包
            Path skillsParent = temp.resolve("skills-parent");
            assertEquals(List.of("plan", "skill-creator"),
                    SkillMaterializer.materializeAll(skillsParent, parent, SkillMaterializer.BUILTIN_SKILL_ROOT));
            assertFalse(Files.exists(skillsParent.resolve("agent-dispatch/skill.md")),
                    "worker 不得看到插件 jar 里的包(child 不在 parent 的委派链上)");

            // 佐证父委派真实存在:child 若去扫 builtin-skill/ 根,会经 parent 看到 worker 的包。
            // 正因如此,插件必须用自己的 skill/ 根——根名隔离是唯一防线,根名一撞就泄漏。
            assertEquals(List.of("plan", "skill-creator"),
                    SkillMaterializer.materializeAll(temp.resolve("skills-deleg"), child,
                            SkillMaterializer.BUILTIN_SKILL_ROOT));
        }
    }

    /** 根在 classpath 上不存在 → 空列表(不抛;物化 id 清单交给调用方日志呈现)。 */
    @Test
    void materializeAllReturnsEmptyWhenRootMissing() throws Exception {
        Path emptyRoot = Files.createDirectories(temp.resolve("empty-cp"));
        try (URLClassLoader cl = new URLClassLoader(new URL[]{emptyRoot.toUri().toURL()}, null)) {
            assertEquals(List.of(), SkillMaterializer.materializeAll(temp.resolve("skills"), cl, "skill"));
        }
    }

    // ---- fixture 辅助 ----

    private static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /**
     * 构造临时 jar:先写齐祖先目录条目(带 / 结尾)——{@code classpath*:} 的根解析依赖目录条目,
     * 缺了它 jar 形态下一个包都发现不到(真实 maven jar 默认带目录条目)。
     */
    private static Path buildJar(Path file, Map<String, byte[]> entries) throws IOException {
        Set<String> dirs = new TreeSet<>();
        for (String path : entries.keySet()) {
            for (int i = path.indexOf('/'); i != -1; i = path.indexOf('/', i + 1)) {
                dirs.add(path.substring(0, i + 1));
            }
        }
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(file))) {
            for (String dir : dirs) {
                jos.putNextEntry(new JarEntry(dir));
                jos.closeEntry();
            }
            for (Map.Entry<String, byte[]> entry : new TreeMap<>(entries).entrySet()) {
                jos.putNextEntry(new JarEntry(entry.getKey()));
                jos.write(entry.getValue());
                jos.closeEntry();
            }
        }
        return file;
    }
}
