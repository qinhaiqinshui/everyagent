package dev.everyagent.worker.skill;

import dev.everyagent.worker.skill.SkillFrontmatter.Frontmatter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link SkillFrontmatter} 纯单测(无 Spring):逐条钉住计划「skill 目录唯一真相源」步骤 3 的
 * 解析规格——有/无/未闭合块、BOM、引号与空值、注释行、未知键忽略(历史遗留的暴露控制键等外来键)、
 * description 截断 200、name 回落目录名,以及易错点「description 回落必须跳过 frontmatter 块本身」。
 */
class SkillFrontmatterTest {

    /** name 回落值:模拟调用方注入的 skill 目录名(id = 目录名)。 */
    private static final String DIR = "my-skill";

    // 1a. 有块:name + description 正常解析(基础形态)
    @Test
    void parsesNameAndDescription() {
        String md = """
                ---
                name: 计划模式
                description: 当任务复杂需拆步骤执行时使用本技能。
                ---

                # 计划模式
                正文从这开始。
                """;
        Frontmatter fm = SkillFrontmatter.parse(md, DIR);
        assertEquals("计划模式", fm.name());
        assertEquals("当任务复杂需拆步骤执行时使用本技能。", fm.description());
    }

    // 1b. 键大小写不敏感
    @Test
    void keyMatchingIsCaseInsensitive() {
        String md = """
                ---
                NAME: 全大写键
                Description: 混大小写键
                ---
                """;
        Frontmatter fm = SkillFrontmatter.parse(md, DIR);
        assertEquals("全大写键", fm.name());
        assertEquals("混大小写键", fm.description());
    }

    // 1c. 值两侧空白剥离 + 成对引号(单/双)剥离
    @Test
    void valueTrimmedAndPairedQuotesStripped() {
        String md = "---\nname:    值两侧有空白   \ndescription: \"双引号值\"\n---\n正文。";
        Frontmatter fm = SkillFrontmatter.parse(md, DIR);
        assertEquals("值两侧有空白", fm.name());
        assertEquals("双引号值", fm.description());

        String md2 = "---\nname: '单引号值'\n---\n正文。";
        assertEquals("单引号值", SkillFrontmatter.parse(md2, DIR).name());
    }

    // 1d. 不成对引号原样保留(只剥离成对引号)
    @Test
    void unpairedQuotesKeptAsIs() {
        String md = "---\nname: \"未闭合引号\n---\n正文。";
        assertEquals("\"未闭合引号", SkillFrontmatter.parse(md, DIR).name());
    }

    // 2. 无块:无元数据,name 回落目录名,description 回落正文首个非空非 # 行
    @Test
    void noFrontmatterFallsBackToDirNameAndFirstBodyLine() {
        String md = "# 标题行\n\n这里是正文首行描述。\n第二行不会被取。";
        Frontmatter fm = SkillFrontmatter.parse(md, DIR);
        assertEquals(DIR, fm.name());
        assertEquals("这里是正文首行描述。", fm.description());
    }

    // 3. 未闭合块(有 --- 起始无终止):视为无元数据——块内键值绝不被解析为元数据
    @Test
    void unclosedBlockTreatedAsNoMetadata() {
        String md = "---\n未闭合,块内容不作为元数据\n";
        Frontmatter fm = SkillFrontmatter.parse(md, DIR);
        assertEquals(DIR, fm.name()); // 键值未被解析,name 仍回落目录名
        // 无块 → 整个文件即正文;回落跳过孤立的 --- 定界行后取首个内容行
        assertEquals("未闭合,块内容不作为元数据", fm.description());

        // 带键值行的未闭合块:块值绝不外漏为元数据
        // (此时无法区分块行与正文,首个内容行按正文处理——降级语义)
        String md2 = "---\nname: 块内名\ndescription: 块内描述\n";
        Frontmatter fm2 = SkillFrontmatter.parse(md2, DIR);
        assertEquals(DIR, fm2.name());
        assertNotEquals("块内名", fm2.name());
        assertNotEquals("块内描述", fm2.description());
    }

    // 4. 首个 --- 前有 UTF-8 BOM:仍能识别块
    @Test
    void bomBeforeFirstDelimiterStillRecognized() {
        String md = "\uFEFF---\nname: BOM技能\n---\n# 标题\n正文。";
        Frontmatter fm = SkillFrontmatter.parse(md, DIR);
        assertEquals("BOM技能", fm.name());

        // BOM + 无块:回落照常
        String md2 = "\uFEFF# 标题\nBOM 后的正文首行。";
        Frontmatter fm2 = SkillFrontmatter.parse(md2, DIR);
        assertEquals(DIR, fm2.name());
        assertEquals("BOM 后的正文首行。", fm2.description());
    }

    // 5. # 注释行跳过:块内注释(含缩进注释)不干扰键值解析;值中段的 # 不是注释
    @Test
    void commentLinesSkippedInsideBlock() {
        String md = "---\n# 块内注释:name 在下一行\n  # 缩进的注释也跳过\nname: 注释之间的键\ndescription: 值中的 # 不是注释\n---\n正文。";
        Frontmatter fm = SkillFrontmatter.parse(md, DIR);
        assertEquals("注释之间的键", fm.name());
        assertEquals("值中的 # 不是注释", fm.description());
    }

    // 6a. 未知键忽略不报错:历史遗留的暴露控制键等外来键静默丢弃,已知键照常解析
    @Test
    void unknownKeysIgnoredSilently() {
        String md = """
                ---
                legacy-expose: true
                version: 1.2.3
                name: 已知键
                description: 未知键被忽略
                ---
                """;
        Frontmatter fm = SkillFrontmatter.parse(md, DIR);
        assertEquals("已知键", fm.name());
        assertEquals("未知键被忽略", fm.description());
    }

    // 6b. 非法键 / 无冒号行等畸形输入:忽略且不抛异常
    @Test
    void malformedLinesIgnoredWithoutError() {
        String md = "---\n中文键: 非法\n1bad: 非法\n孤立文本行\n: 冒号打头\nname: 合法\n---\n正文。";
        Frontmatter fm = SkillFrontmatter.parse(md, DIR);
        assertEquals("合法", fm.name());
        assertEquals("正文。", fm.description()); // 块内无 description 键 → 正文回落
    }

    // 7. key: 空值 = 未提供(name 空值回落目录名;description 空值走正文回落)
    @Test
    void emptyValueMeansNotProvided() {
        String md = """
                ---
                name:
                description:
                ---

                空值后正文首行。
                """;
        Frontmatter fm = SkillFrontmatter.parse(md, DIR);
        assertEquals(DIR, fm.name());
        assertEquals("空值后正文首行。", fm.description());

        // 纯空白值 / 空引号对:同样视为未提供
        String md2 = "---\nname:   \ndescription: \"\"\n---\n引号空值后正文。";
        Frontmatter fm2 = SkillFrontmatter.parse(md2, DIR);
        assertEquals(DIR, fm2.name());
        assertEquals("引号空值后正文。", fm2.description());
    }

    // 8. description 截断 200 字符(块内值与正文回落都截断)
    @Test
    void descriptionTruncatedTo200() {
        String longDesc = "长".repeat(250);

        String md = "---\ndescription: " + longDesc + "\n---\n正文。";
        Frontmatter fm = SkillFrontmatter.parse(md, DIR);
        assertEquals(200, fm.description().length());
        assertEquals("长".repeat(200), fm.description());

        String md2 = longDesc + "\n第二行。"; // 无块,正文回落同样截断
        assertEquals(200, SkillFrontmatter.parse(md2, DIR).description().length());
    }

    // 9. name 缺省回落目录名(调用方注入的 fallback 生效)
    @Test
    void missingNameFallsBackToCallerProvidedDirName() {
        String md = "---\ndescription: 只有描述\n---\n# 标题\n正文。";
        Frontmatter fm = SkillFrontmatter.parse(md, "plan");
        assertEquals("plan", fm.name());
        assertEquals("只有描述", fm.description());
    }

    // 10. 【易错点钉子】有块、无 description、正文首行是 # 标题 → 回落取跳过标题后的
    //     首个非空非 # 行,绝不取块内任何 key: value 行(必须跳过 frontmatter 块本身)
    @Test
    void descriptionFallbackSkipsFrontmatterBlockItself() {
        String md = """
                ---
                name: 有名无述
                ---

                # 正文标题

                跳过标题后的首个非空非 # 行。
                后续行不会被取。
                """;
        Frontmatter fm = SkillFrontmatter.parse(md, DIR);
        assertEquals("跳过标题后的首个非空非 # 行。", fm.description());
        assertNotEquals("name: 有名无述", fm.description());
    }

    // 11. 多行正文、空行开头的正文回落跳过空行(含缩进/制表空白行与行首缩进)
    @Test
    void fallbackSkipsLeadingBlankLines() {
        String md = "\n\n   \n\t\n正文首行。\n第二行。";
        assertEquals("正文首行。", SkillFrontmatter.parse(md, DIR).description());

        String md2 = "---\nname: 块后空行\n---\n\n\n  缩进的正文首行。\n第二行。";
        assertEquals("缩进的正文首行。", SkillFrontmatter.parse(md2, DIR).description());
    }

    // 补充:永不抛异常 + 退化输入(null / 空串 / 孤立 --- / 空块)全部降级为无元数据 + 正文回落
    @Test
    void neverThrowsOnDegenerateInput() {
        Frontmatter fmNull = SkillFrontmatter.parse(null, DIR);
        assertEquals(DIR, fmNull.name());
        assertEquals("", fmNull.description());

        Frontmatter fmEmpty = SkillFrontmatter.parse("", DIR);
        assertEquals(DIR, fmEmpty.name());
        assertEquals("", fmEmpty.description());

        // 单个 "---" 行(起始无终止 → 未闭合):name 回落;正文无内容 → 空串
        Frontmatter fmBare = SkillFrontmatter.parse("---", DIR);
        assertEquals(DIR, fmBare.name());
        assertEquals("", fmBare.description());

        // 空块(--- 紧跟 ---):视为有块但无键,正文回落照常
        Frontmatter fmEmptyBlock = SkillFrontmatter.parse("---\n---\n空块后的正文。", DIR);
        assertEquals(DIR, fmEmptyBlock.name());
        assertEquals("空块后的正文。", fmEmptyBlock.description());
    }

    // 补充:CRLF 行尾(Windows 磁盘文件)与带尾随空白的定界行
    @Test
    void crlfAndSloppyDelimitersSupported() {
        String md = "---\r\nname: CRLF技能\r\ndescription: CRLF 描述\r\n---\r\n# 标题\r\nCRLF 正文。";
        Frontmatter fm = SkillFrontmatter.parse(md, DIR);
        assertEquals("CRLF技能", fm.name());
        assertEquals("CRLF 描述", fm.description());

        String md2 = "---  \nname: 尾空白定界\n---\t\n正文。";
        assertEquals("尾空白定界", SkillFrontmatter.parse(md2, DIR).name());
    }
}
