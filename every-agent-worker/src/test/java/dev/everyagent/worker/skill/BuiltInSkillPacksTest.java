package dev.everyagent.worker.skill;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.worker.config.WorkerProperties;

/**
 * BuiltInSkillPacks 物化测试:两包落盘形态、幂等、等长改写复原、被删复原、
 * 旧扁平残留清理、越界防御(落盘永不越出 skillsDir)、materialize() 返回 id 集合,
 * 以及「正文与 frontmatter 迁移前逐字节一致」的迁移钉子。
 *
 * <p>用 @TempDir 构造 skillsDir,手动 new BuiltInSkillPacks(不经 Spring 生命周期,
 * 直接调 {@link BuiltInSkillPacks#materialize()})。
 */
class BuiltInSkillPacksTest {

    /**
     * 迁移钉子:两包去 frontmatter 后正文(行尾按仓库策略归一为 LF)的 SHA-256。
     * 迁移只加了头部,正文内容一字未动;hash 前做 CRLF→LF 归一是因仓库
     * {@code .gitattributes} 统一 {@code eol=crlf}(索引存 LF、checkout 转 CRLF),
     * 不归一的话同一份文件在 LF/CRLF 工作区会算出不同 hash。
     * 若日后有意修改正文,请连同此常量一起更新。
     *
     * <p>两包的钉子基线不同:
     * <ul>
     *   <li>{@code plan}:自旧扁平资源 {@code skill/plan.md} 迁移以来正文从未改动,
     *       hash = 迁移前(旧资源)正文 hash;「只加头部、正文不动」的回归护栏。</li>
     *   <li>{@code skill-creator}:迁移时同样只加头部;但其知识包正文在文档收尾步
     *       (教 AI 新 frontmatter 写法:仅 name/description、无暴露字段)被<b>有意整体改写</b>,
     *       故此处置换为改写后正文的 hash——钉住「现状正文」,此后任何再改动(无论有意
     *       或误改)都须显式连此常量一起更新,误改即红灯。</li>
     * </ul>
     */
    private static final String PLAN_BODY_SHA256 =
            "4E48F7A4DED1281625E86BA82C2BC6DEF14BCA24443127707CC77A7E5301B489";
    private static final String SKILL_CREATOR_BODY_SHA256 =
            "AFEA1E076D8F124072E8C50AAD26B4C169C92BFD0ACE995691C87CBB41BECA53";

    private static BuiltInSkillPacks newPacks(Path skillsDir) {
        WorkerProperties props = new WorkerProperties();
        props.setSkillsDir(skillsDir.toString());
        return new BuiltInSkillPacks(props);
    }

    @Test
    void materializeCreatesDirectoryFormAndReturnsIds(@TempDir Path tmp) throws Exception {
        Path skillsDir = tmp.resolve("skills"); // 预置不存在:物化应连目录一起创建
        BuiltInSkillPacks packs = newPacks(skillsDir);

        List<String> ids = packs.materialize();

        assertEquals(List.of("plan", "skill-creator"), ids,
                "worker 内置根 builtin-skill/ 下只有两包(不含 agent-dispatch——那是插件包,不在 worker classpath)");
        for (String id : ids) {
            Path target = skillsDir.resolve(id).resolve("skill.md");
            assertTrue(Files.isRegularFile(target), "物化产物应为目录形态 <id>/skill.md: " + target);
            assertTrue(Files.size(target) > 0, "物化文件非空: " + target);
            assertFalse(Files.isRegularFile(skillsDir.resolve(id + ".md")),
                    "不应残留扁平 <id>.md: " + id + ".md");
        }
    }

    @Test
    void materializeIdempotent(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        BuiltInSkillPacks packs = newPacks(skillsDir);

        List<String> first = packs.materialize();
        Path planMd = skillsDir.resolve("plan").resolve("skill.md");
        byte[] content = Files.readAllBytes(planMd);

        List<String> second = packs.materialize();
        packs.materialize(); // 第三次仍幂等

        assertEquals(first, second, "幂等零写入也返回同一 id 清单");
        assertArrayEquals(content, Files.readAllBytes(planMd), "重复物化后内容逐字节一致");
    }

    @Test
    void equalLengthTamperIsOverwritten(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        BuiltInSkillPacks packs = newPacks(skillsDir);
        packs.materialize();

        Path planMd = skillsDir.resolve("plan").resolve("skill.md");
        byte[] expected = Files.readAllBytes(planMd);
        // 篡改一个字节:长度不变、内容变化(旧「大小一致则跳过」判据会漏掉这种改动)
        byte[] tampered = expected.clone();
        int idx = tampered.length / 2;
        tampered[idx] = (byte) (tampered[idx] ^ 0x01);
        Files.write(planMd, tampered);
        assertEquals(tampered.length, expected.length, "篡改后长度不变");
        assertFalse(Arrays.equals(expected, Files.readAllBytes(planMd)));

        packs.materialize();

        assertArrayEquals(expected, Files.readAllBytes(planMd),
                "等长改写必须覆盖复原(幂等判据是逐字节内容,不是大小)");
    }

    @Test
    void deletedPackIsRestored(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        BuiltInSkillPacks packs = newPacks(skillsDir);
        packs.materialize();

        Path planMd = skillsDir.resolve("plan").resolve("skill.md");
        Path creatorMd = skillsDir.resolve("skill-creator").resolve("skill.md");
        byte[] planExpected = Files.readAllBytes(planMd);
        byte[] creatorExpected = Files.readAllBytes(creatorMd);

        Files.delete(planMd); // 用户/AI 误删
        Files.delete(creatorMd);
        packs.materialize();

        assertArrayEquals(planExpected, Files.readAllBytes(planMd), "被删除的内置包应被重新物化");
        assertArrayEquals(creatorExpected, Files.readAllBytes(creatorMd));
    }

    @Test
    void legacyFlatResidueCleaned(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        BuiltInSkillPacks packs = newPacks(skillsDir);
        // 手动放置旧扁平残留(目录形态迁移前的布局)
        Path legacyPlan = Files.writeString(skillsDir.resolve("plan.md"), "旧扁平残留内容\n").toAbsolutePath().normalize();
        Path legacyCreator = Files.writeString(skillsDir.resolve("skill-creator.md"), "旧扁平残留内容\n").toAbsolutePath().normalize();

        packs.materialize();

        assertFalse(Files.exists(legacyPlan), "旧扁平 plan.md 应被清理");
        assertFalse(Files.exists(legacyCreator), "旧扁平 skill-creator.md 应被清理");
        assertTrue(Files.isRegularFile(skillsDir.resolve("plan").resolve("skill.md")));
    }

    /**
     * 越界防御:物化产物必须全部落在 skillsDir 子树内(目录外一个字节都不写)。
     * id 白名单经 {@code [a-z0-9][a-z0-9-]*} 校验 + 前缀越界拒绝(见 SkillMaterializer),
     * 这里从外部视角钉住结果:扫 tmp 全树,任何文件都必须以 skillsDir 为前缀。
     */
    @Test
    void materializeNeverWritesOutsideSkillsDir(@TempDir Path tmp) throws Exception {
        Path skillsDir = tmp.resolve("nested").resolve("skills"); // 深层嵌套,越界必然可见
        BuiltInSkillPacks packs = newPacks(skillsDir);

        packs.materialize();

        try (Stream<Path> all = Files.walk(tmp)) {
            List<Path> outside = all.filter(Files::isRegularFile)
                    .filter(p -> !p.toAbsolutePath().normalize().startsWith(skillsDir))
                    .toList();
            assertTrue(outside.isEmpty(), "skillsDir 之外不得有任何落盘产物: " + outside);
        }
        assertEquals(2, countFiles(skillsDir), "skillsDir 内应恰好两份 skill.md");
    }

    /**
     * 迁移钉子:物化产物 = classpath 源逐字节一致;且去掉 frontmatter 头后的正文
     * (行尾归一)SHA-256 与钉子常量一致——plan 钉「迁移前正文」(只加头部、正文不动
     * 的回归护栏),skill-creator 钉「知识包有意改写后的现状正文」(见常量 javadoc)。
     * 行尾归一理由见常量 javadoc。
     */
    @Test
    void bodiesByteIdenticalToPreMigration(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        newPacks(skillsDir).materialize();

        assertBodyUnchanged(skillsDir, "plan", PLAN_BODY_SHA256);
        assertBodyUnchanged(skillsDir, "skill-creator", SKILL_CREATOR_BODY_SHA256);
    }

    private static void assertBodyUnchanged(Path skillsDir, String id, String expectedSha256)
            throws Exception {
        Path materialized = skillsDir.resolve(id).resolve("skill.md");
        byte[] disk = Files.readAllBytes(materialized);

        // 1) 物化产物与 classpath 源逐字节一致(物化通道不篡改内容)
        byte[] source;
        try (InputStream in = BuiltInSkillPacksTest.class.getClassLoader()
                .getResourceAsStream("builtin-skill/" + id + "/skill.md")) {
            assertTrue(in != null, "classpath 源应存在: builtin-skill/" + id + "/skill.md");
            source = in.readAllBytes();
        }
        assertArrayEquals(source, disk, id + " 物化产物应与 classpath 源逐字节一致");

        // 2) 去掉 frontmatter 头后的正文(行尾归一)与迁移前逐字节一致
        String text = new String(disk, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("---"), id + " 应带 frontmatter 头");
        String body = bodyAfterFrontmatter(text).replace("\r\n", "\n");
        String sha = HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expectedSha256, sha, id + " 去头正文 hash 应与迁移前一致(正文一字未动)");
    }

    /** 剥离首部 frontmatter 块(--- 起、下一个 --- 止)及其后的分隔空行;无块则原样返回。 */
    private static String bodyAfterFrontmatter(String text) {
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = text.split("\r\n|\n", -1);
        if (lines.length == 0 || !lines[0].strip().equals("---")) {
            return text;
        }
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].strip().equals("---")) {
                int bodyStart = i + 1;
                if (bodyStart < lines.length && lines[bodyStart].isBlank()) {
                    bodyStart++; // 头部与正文之间的分隔空行不属于原正文
                }
                return String.join(nl, Arrays.copyOfRange(lines, bodyStart, lines.length));
            }
        }
        return text; // 未闭合:整个文件即正文
    }

    private static long countFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }
}
