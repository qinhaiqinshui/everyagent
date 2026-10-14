package dev.everyagent.worker.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.worker.config.WorkerProperties;

/**
 * SkillCatalog 单测:目录即真相源的扫描规则——正常扫描、缺 skill.md 跳过、非法目录名、
 * realpath 越界(符号链接/junction 逃逸)、frontmatter 元数据、缺 frontmatter 回落、
 * 非递归、scan() 每次实扫(不缓存,新增目录立即可见)。
 *
 * <p>路径断言一律用 {@link Path} 语义(修掉旧 {@code endsWith("code-review/skill.md")}
 * 字符串断言在 Windows 上恒假的缺陷)。{@code @TempDir} 构造自定义 skillsDir,
 * 手动 new SkillCatalog(不经 Spring 生命周期)。
 */
class SkillCatalogTest {

    private static SkillCatalog newCatalog(Path skillsDir) {
        WorkerProperties props = new WorkerProperties();
        props.setSkillsDir(skillsDir.toString());
        return new SkillCatalog(props);
    }

    @Test
    void normalSkillWithFrontmatter(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        Path dir = Files.createDirectory(skillsDir.resolve("code-review"));
        Path skillMd = dir.resolve("skill.md");
        Files.writeString(skillMd, "---\nname: 代码审查\ndescription: 对代码变更进行审查，给出改进建议。\n---\n\n# 代码审查\n正文。\n");

        List<Skill> skills = newCatalog(skillsDir).scan();

        assertEquals(1, skills.size());
        Skill s = skills.get(0);
        assertEquals("code-review", s.id());
        assertEquals("代码审查", s.title(), "title 取 frontmatter name");
        assertEquals("对代码变更进行审查，给出改进建议。", s.description());
        assertEquals(skillMd.toAbsolutePath().normalize(), Path.of(s.knowledgePath()),
                "knowledgePath 用 Path 语义断言(Windows 反斜杠下字符串 endsWith 恒假)");
    }

    @Test
    void missingSkillMdSkipped(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        Files.createDirectory(skillsDir.resolve("empty")); // 目录存在但无 skill.md
        assertTrue(newCatalog(skillsDir).scan().isEmpty());
    }

    @Test
    void badDirNameSkipped(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        Path upper = Files.createDirectory(skillsDir.resolve("BadName"));
        Files.writeString(upper.resolve("skill.md"), "描述\n");
        Path special = Files.createDirectory(skillsDir.resolve("under_score"));
        Files.writeString(special.resolve("skill.md"), "描述\n");
        Path dotted = Files.createDirectory(skillsDir.resolve("has.dot"));
        Files.writeString(dotted.resolve("skill.md"), "描述\n");
        assertTrue(newCatalog(skillsDir).scan().isEmpty(),
                "大写/下划线/含点目录名均非法,应全部跳过");
    }

    /**
     * realpath 越界:skillsDir 下指向外部目录的链接必须被拒绝。
     * Windows 优先尝试符号链接,权限不足时回退 junction(无需特权);
     * 两者都不可用时跳过本用例(沙箱受限环境)。
     */
    @Test
    void symlinkEscapeSkipped(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        Path outsideDir = Files.createDirectory(tmp.resolve("outside"));
        Files.writeString(outsideDir.resolve("skill.md"), "越界内容\n");

        Path link = skillsDir.resolve("escape");
        assumeTrue(createDirLink(link, outsideDir),
                "环境不支持符号链接/junction,跳过 realpath 越界用例");

        assertTrue(newCatalog(skillsDir).scan().isEmpty(), "越界链接目录应被跳过");
    }

    /** 优先符号链接,失败回退 junction(mklink /J,Windows 无需特权);都失败返回 false。 */
    private static boolean createDirLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException symlinkFailed) {
            // 回退 junction
        }
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    link.toString(), target.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
            p.waitFor();
            return p.exitValue() == 0 && Files.isDirectory(link);
        } catch (Exception junctionFailed) {
            return false;
        }
    }

    @Test
    void frontmatterTitleAndDescription(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        writeSkill(skillsDir, "quoted",
                "---\nname: \"带引号标题\"\ndescription: '单引号描述'\n---\n# 标题\n正文\n");
        writeSkill(skillsDir, "empty-name",
                "---\nname:\ndescription: 显式描述\n---\n# 标题\n正文\n");

        List<Skill> skills = newCatalog(skillsDir).scan();

        assertEquals(2, skills.size());
        assertEquals(List.of("empty-name", "quoted"),
                skills.stream().map(Skill::id).toList(), "按 id 升序(确定序)");
        Skill quoted = skills.get(1);
        assertEquals("带引号标题", quoted.title(), "成对引号被剥离");
        assertEquals("单引号描述", quoted.description());
        assertEquals("显式描述", skills.get(0).description());
    }

    @Test
    void missingFrontmatterFallsBack(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        Path dir = Files.createDirectory(skillsDir.resolve("legacy"));
        Files.writeString(dir.resolve("skill.md"),
                "# 标题行被跳过\n\n首个非空且非 # 的正文行当描述。\n后续行忽略。\n");

        List<Skill> skills = newCatalog(skillsDir).scan();

        assertEquals(1, skills.size());
        Skill s = skills.get(0);
        assertEquals("legacy", s.title(), "缺 frontmatter 时 title 回落目录名");
        assertEquals("首个非空且非 # 的正文行当描述。", s.description(),
                "缺 frontmatter 时 description 回落首个非标题正文行");
    }

    @Test
    void nonRecursiveSecondLevelIgnored(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        // 一级目录 outer 无 skill.md,二级目录 inner 的 skill.md 不算
        Path inner = Files.createDirectories(skillsDir.resolve("outer").resolve("inner"));
        Files.writeString(inner.resolve("skill.md"), "# 嵌套\n二级子目录里的 skill.md 不应被发现。\n");

        assertTrue(newCatalog(skillsDir).scan().isEmpty(), "非递归:二级子目录不算 skill");
    }

    @Test
    void scanSeesNewlyAddedSkill(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        writeSkill(skillsDir, "one", "# one\n第一条\n");
        SkillCatalog catalog = newCatalog(skillsDir);
        assertEquals(1, catalog.scan().size());

        writeSkill(skillsDir, "two", "# two\n第二条\n");
        assertEquals(2, catalog.scan().size(), "不缓存:scan() 每次实扫,新增目录立即可见");
    }

    @Test
    void nonExistentRootReturnsEmpty(@TempDir Path tmp) {
        SkillCatalog catalog = newCatalog(tmp.resolve("does-not-exist"));
        assertNotNull(catalog.scan());
        assertTrue(catalog.scan().isEmpty());
    }

    private static void writeSkill(Path skillsDir, String id, String content) throws IOException {
        Path dir = Files.createDirectory(skillsDir.resolve(id));
        Files.writeString(dir.resolve("skill.md"), content);
    }
}
