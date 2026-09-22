package dev.everyagent.worker.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.worker.config.WorkerProperties;

/**
 * BuiltInSkills 物化测试:验证目录化形态、幂等、旧扁平残留清理。
 *
 * <p>用 @TempDir 构造 skillsDir,手动 new WorkerProperties 设置 skillsDir,
 * 直接调用 {@link BuiltInSkills#materialize()}(不经 Spring 生命周期)。
 */
class BuiltInSkillsTest {

    private static BuiltInSkills newBuiltInSkills(Path skillsDir) {
        WorkerProperties props = new WorkerProperties();
        props.setSkillsDir(skillsDir.toString());
        return new BuiltInSkills(props);
    }

    @Test
    void materializeCreatesDirectoryForm(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        BuiltInSkills bis = newBuiltInSkills(skillsDir);

        bis.materialize();

        // 内置 skill 有两个:agent-dispatch、plan,物化形态为 <skillsDir>/<id>/skill.md
        for (Skill s : bis.getActiveSkills()) {
            Path target = skillsDir.resolve(s.id()).resolve("skill.md");
            assertTrue(Files.isRegularFile(target),
                    "物化产物应为目录形态 <id>/skill.md: " + target);
            assertFalse(Files.isRegularFile(skillsDir.resolve(s.id() + ".md")),
                    "不应残留扁平 <id>.md: " + s.id() + ".md");
            assertTrue(Files.size(target) > 0, "物化文件非空: " + target);
        }
    }

    @Test
    void materializeIdempotent(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        BuiltInSkills bis = newBuiltInSkills(skillsDir);

        bis.materialize();
        // 记录首次物化后各文件的内容摘要(大小 + 内容)
        Path agentMd = skillsDir.resolve("agent-dispatch").resolve("skill.md");
        Path planMd = skillsDir.resolve("plan").resolve("skill.md");
        String agentContent = Files.readString(agentMd);
        String planContent = Files.readString(planMd);

        // 重复物化不报错
        bis.materialize();
        bis.materialize();

        // 内容一致(幂等)
        assertEquals(agentContent, Files.readString(agentMd),
                "重复物化后 agent-dispatch/skill.md 内容一致");
        assertEquals(planContent, Files.readString(planMd),
                "重复物化后 plan/skill.md 内容一致");
    }

    @Test
    void materializeDeletesLegacyFlatFile(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        BuiltInSkills bis = newBuiltInSkills(skillsDir);

        // 手动放置旧扁平残留(目录形态迁移前的残留)
        Path legacyAgent = skillsDir.resolve("agent-dispatch.md");
        Path legacyPlan = skillsDir.resolve("plan.md");
        Files.writeString(legacyAgent, "旧扁平残留内容\n");
        Files.writeString(legacyPlan, "旧扁平残留内容\n");
        assertTrue(Files.exists(legacyAgent));
        assertTrue(Files.exists(legacyPlan));

        bis.materialize();

        // 旧扁平文件被清理
        assertFalse(Files.exists(legacyAgent), "旧扁平 agent-dispatch.md 应被清理");
        assertFalse(Files.exists(legacyPlan), "旧扁平 plan.md 应被清理");
        // 目录形态产物存在
        assertTrue(Files.isRegularFile(skillsDir.resolve("agent-dispatch").resolve("skill.md")));
        assertTrue(Files.isRegularFile(skillsDir.resolve("plan").resolve("skill.md")));
    }

    @Test
    void activeSkillsAreTwoBuiltin(@TempDir Path tmp) {
        Path skillsDir = tmp.resolve("skills");
        BuiltInSkills bis = newBuiltInSkills(skillsDir);

        List<Skill> active = bis.getActiveSkills();
        assertEquals(2, active.size());
        assertEquals("agent-dispatch", active.get(0).id());
        assertEquals("plan", active.get(1).id());
    }

    @Test
    void knowledgeRootResolvesToSkillsDir(@TempDir Path tmp) {
        Path skillsDir = tmp.resolve("skills");
        BuiltInSkills bis = newBuiltInSkills(skillsDir);

        assertEquals(skillsDir.toAbsolutePath().normalize(), bis.getKnowledgeRoot());
    }
}
