package dev.everyagent.worker.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.worker.config.WorkerProperties;

/**
 * ExternalSkillScanner 单测。用 @TempDir 构造自定义 skillsDir,手动 new BuiltInSkills
 * (重写 materialize 为空避免物化副作用)再构造 scanner。
 */
class ExternalSkillScannerTest {

    /** 测试用 BuiltInSkills:跳过 @PostConstruct materialize 的物化逻辑。 */
    private static BuiltInSkills testBuiltInSkills(Path skillsDir) {
        WorkerProperties props = new WorkerProperties();
        props.setSkillsDir(skillsDir.toString());
        return new BuiltInSkills(props) {
            @Override
            public void materialize() {
                // no-op: 测试不依赖 classpath 物化
            }
        };
    }

    private static ExternalSkillScanner newScanner(Path skillsDir) {
        ExternalSkillScanner scanner = new ExternalSkillScanner(testBuiltInSkills(skillsDir));
        scanner.init(); // 显式触发扫描(测试不走 Spring 生命周期)
        return scanner;
    }

    @Test
    void normalSkill(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        Path dir = Files.createDirectory(skillsDir.resolve("code-review"));
        Files.writeString(dir.resolve("skill.md"),
                "# Code Review Skill\n\n对代码变更进行审查,给出改进建议。\n");
        ExternalSkillScanner scanner = newScanner(skillsDir);
        List<Skill> skills = scanner.scan();
        assertEquals(1, skills.size());
        Skill s = skills.get(0);
        assertEquals("code-review", s.id());
        assertEquals("code-review", s.title());
        assertEquals("对代码变更进行审查,给出改进建议。", s.description());
        assertTrue(s.knowledgePath().endsWith("code-review/skill.md"));
        assertTrue(s.toolIds().isEmpty());
    }

    @Test
    void missingSkillMdSkipped(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        Files.createDirectory(skillsDir.resolve("empty")); // 无 skill.md
        ExternalSkillScanner scanner = newScanner(skillsDir);
        assertTrue(scanner.scan().isEmpty());
    }

    @Test
    void badDirNameSkipped(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        Path upper = Files.createDirectory(skillsDir.resolve("BadName"));
        Files.writeString(upper.resolve("skill.md"), "描述\n");
        Path special = Files.createDirectory(skillsDir.resolve("under_score"));
        Files.writeString(special.resolve("skill.md"), "描述\n");
        ExternalSkillScanner scanner = newScanner(skillsDir);
        assertTrue(scanner.scan().isEmpty());
    }

    @Test
    void builtinIdSkipped(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        Path dir = Files.createDirectory(skillsDir.resolve("agent-dispatch"));
        Files.writeString(dir.resolve("skill.md"), "假装是内置 skill 的外部目录\n");
        ExternalSkillScanner scanner = newScanner(skillsDir);
        assertTrue(scanner.scan().isEmpty());
    }

    @Test
    void descriptionTruncated(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        Path dir = Files.createDirectory(skillsDir.resolve("long-desc"));
        String longLine = "x".repeat(300);
        Files.writeString(dir.resolve("skill.md"), "# Title\n\n" + longLine + "\n");
        ExternalSkillScanner scanner = newScanner(skillsDir);
        List<Skill> skills = scanner.scan();
        assertEquals(1, skills.size());
        assertEquals(200, skills.get(0).description().length());
    }

    @Test
    void descriptionEmptyWhenOnlyHeadings(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        Path dir = Files.createDirectory(skillsDir.resolve("only-headings"));
        Files.writeString(dir.resolve("skill.md"), "# Title\n## Sub\n\n# Another\n");
        ExternalSkillScanner scanner = newScanner(skillsDir);
        List<Skill> skills = scanner.scan();
        assertEquals(1, skills.size());
        assertEquals("", skills.get(0).description());
    }

    @Test
    void nonExistentRootReturnsEmpty(@TempDir Path tmp) {
        Path skillsDir = tmp.resolve("does-not-exist");
        ExternalSkillScanner scanner = newScanner(skillsDir);
        assertTrue(scanner.scan().isEmpty());
    }

    @Test
    void emptyRootReturnsEmpty(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        ExternalSkillScanner scanner = newScanner(skillsDir);
        assertTrue(scanner.scan().isEmpty());
    }

    @Test
    void multipleSkills(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        writeSkill(skillsDir, "one", "第一条 skill\n");
        writeSkill(skillsDir, "two", "第二条 skill\n");
        ExternalSkillScanner scanner = newScanner(skillsDir);
        assertEquals(2, scanner.scan().size());
    }

    @Test
    void scanNeverReturnsNull(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        ExternalSkillScanner scanner = newScanner(skillsDir);
        assertNotNull(scanner.scan());
        // scanOnce 也不返回 null
        assertNotNull(scanner.scanOnce());
    }

    /**
     * 越界符号链接:若环境支持创建符号链接,验证 realpath 越界被跳过;
     * 不支持则跳过本用例(不在沙箱受限环境上报错)。
     */
    @Test
    void symlinkEscapeSkipped(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        Path outsideDir = Files.createDirectory(tmp.resolve("outside"));
        Files.writeString(outsideDir.resolve("skill.md"), "越界内容\n");

        Path link = skillsDir.resolve("escape");
        try {
            Files.createSymbolicLink(link, outsideDir);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            // 沙箱/不支持符号链接:跳过
            return;
        }
        if (!Files.isSymbolicLink(link)) {
            return; // 创建后仍非链接(权限受限),跳过
        }
        ExternalSkillScanner scanner = newScanner(skillsDir);
        assertTrue(scanner.scan().isEmpty(), "越界符号链接应被跳过");
    }

    private static void writeSkill(Path skillsDir, String id, String body) throws Exception {
        Path dir = Files.createDirectory(skillsDir.resolve(id));
        Files.writeString(dir.resolve("skill.md"), "# " + id + "\n\n" + body);
    }
}
