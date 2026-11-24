package dev.everyagent.worker.agent;

import dev.everyagent.worker.tools.FileTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolArgumentsSchema} 单测:必填缺失判定 / 疑似改名提示 / 放行分支。
 *
 * <p>前提用例用<b>真实框架 schema</b>(MethodToolCallbackProvider 按 FileTools 的 @ToolParam 生成)
 * 固化「inputSchema 携带 required 清单」——Spring AI 升级若改变该事实本测试即红灯。
 */
class ToolArgumentsSchemaTest {

    // ── 测试设施 ──

    private static ToolCallback real(String toolName) {
        ToolCallback[] all = MethodToolCallbackProvider.builder()
                .toolObjects(new FileTools(null, null, "main")).build().getToolCallbacks();
        return Arrays.stream(all).filter(c -> toolName.equals(c.getToolDefinition().name())).findFirst()
                .orElseThrow();
    }

    private static ToolCallback withSchema(String name, String schema) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("test").inputSchema(schema).build();
            }
            @Override public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
                return org.springframework.ai.tool.metadata.ToolMetadata.builder().build();
            }
            @Override public String call(String toolInput) { throw new UnsupportedOperationException(); }
        };
    }

    private static final String UPDATE_SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"path\":{\"type\":\"string\"},\"oldcontent\":{\"type\":\"string\"},\"newcontent\":{\"type\":\"string\"}},"
            + "\"required\":[\"path\",\"oldcontent\",\"newcontent\"]}";

    // ── 前提:框架 schema 带 required(2026-12 t_jrm6 事故的护栏依据) ──

    @Test
    void frameworkSchemaCarriesRequiredListForUpdateFile() {
        String schema = real("update_file").getToolDefinition().inputSchema();
        assertTrue(schema.contains("\"required\""), "MethodToolCallbackProvider 生成的 schema 必须含 required: " + schema);
        // 参数已更名 newcontent:模型按旧习惯写 content → 必须判缺失并给出改名提示
        String problem = ToolArgumentsSchema.missingRequired(real("update_file"),
                "{\"path\":\"f.md\",\"oldcontent\":\"a\",\"content\":\"b\"}");
        assertNotNull(problem, "真实框架 schema 下,content 顶替 newcontent 必须判缺失");
        assertTrue(problem.contains("newcontent") && problem.contains("content"), problem);
    }

    @Test
    void frameworkSchemaCarriesRequiredForCreateFile() {
        String problem = ToolArgumentsSchema.missingRequired(real("create_file"), "{\"path\":\"a.md\"}");
        assertNotNull(problem, "create_file 缺 content 必须判缺失");
    }

    // ── 判定分支 ──

    @Test
    void validArgumentsPass() {
        assertNull(ToolArgumentsSchema.missingRequired(withSchema("update_file", UPDATE_SCHEMA),
                "{\"path\":\"f.md\",\"oldcontent\":\"a\",\"newcontent\":\"b\"}"));
    }

    @Test
    void missingKeyIsReportedWithRenameHint() {
        // 旧习惯名 content 顶替 newcontent(t_jrm6 事故形态在更名后的镜像)
        String problem = ToolArgumentsSchema.missingRequired(withSchema("update_file", UPDATE_SCHEMA),
                "{\"content\":\"b\",\"oldcontent\":\"a\",\"path\":\"f.md\"}");
        assertTrue(problem.startsWith("工具参数校验失败:"), problem);
        assertTrue(problem.contains("缺少必填参数 newcontent"), problem);
        assertTrue(problem.contains("是否想说 newcontent？本次收到的参数名是 content"), problem);
        assertTrue(problem.contains("未定义的参数名:content"), problem);
        assertTrue(problem.contains("update_file"), problem);
    }

    @Test
    void jsonNullValueCountsAsMissing() {
        String problem = ToolArgumentsSchema.missingRequired(withSchema("update_file", UPDATE_SCHEMA),
                "{\"path\":\"f.md\",\"oldcontent\":\"a\",\"newcontent\":null}");
        assertNotNull(problem);
        assertTrue(problem.contains("缺少必填参数 newcontent"), problem);
    }

    @Test
    void blankArgumentsTreatedAsEmptyObjectAndListEveryMissingKey() {
        List<String> missing = List.of("path", "oldcontent", "newcontent");
        String problem = ToolArgumentsSchema.describe("update_file", missing, List.of(), true);
        assertTrue(problem.contains("缺少必填参数 path; 缺少必填参数 oldcontent; 缺少必填参数 newcontent"), problem);
        assertFalse(problem.contains("是否想说"), "无多余键时不应有改名提示");
        assertNotNull(ToolArgumentsSchema.missingRequired(withSchema("update_file", UPDATE_SCHEMA), "  "),
                "空白入参按 {} 归一 → 全部必填缺失");
    }

    @Test
    void extraKeysAloneDoNotFail() {
        assertNull(ToolArgumentsSchema.missingRequired(withSchema("update_file", UPDATE_SCHEMA),
                "{\"path\":\"f.md\",\"oldcontent\":\"a\",\"newcontent\":\"b\",\"content\":\"ignored\"}"),
                "多余键只作提示,不改变「未知键被忽略」的框架语义");
    }

    // ── 放行分支(交框架原生路径) ──

    @Test
    void nonObjectOrUnparsableArgumentsPassThrough() {
        ToolCallback cb = withSchema("t", UPDATE_SCHEMA);
        assertNull(ToolArgumentsSchema.missingRequired(cb, "not json"));
        assertNull(ToolArgumentsSchema.missingRequired(cb, "[1,2]"));
        assertNull(ToolArgumentsSchema.missingRequired(cb, "\"text\""));
    }

    @Test
    void schemaWithoutRequiredOrUnusablePassesThrough() {
        assertNull(ToolArgumentsSchema.missingRequired(withSchema("t", "{\"type\":\"object\",\"properties\":{}}"),
                "{\"other\":1}"), "无 required → 无可校验之物");
        assertNull(ToolArgumentsSchema.missingRequired(withSchema("t", "{}"), "{}"));
        assertNull(ToolArgumentsSchema.missingRequired(withSchema("t", "not json"), "{}"), "schema 不可解析 → 放行");
        assertNull(ToolArgumentsSchema.missingRequired(null, "{}"), "回调缺席 → 放行");
    }

    @Test
    void propertiesAbsentMeansNoUnknownListButRenameHintStillGiven() {
        String problem = ToolArgumentsSchema.missingRequired(
                withSchema("t", "{\"type\":\"object\",\"required\":[\"content\"]}"),
                "{\"newcontent\":\"x\"}");
        assertNotNull(problem);
        assertTrue(problem.contains("是否想说 content？本次收到的参数名是 newcontent"),
                "无 properties 时仍以「不在 required 的键」为改名候选: " + problem);
        assertFalse(problem.contains("未定义的参数名"), "无 properties 不谈「未定义」: " + problem);
    }

    // ── 疑似改名 ──

    @Test
    void closestPicksMostLikelyRename() {
        assertEquals("newcontent", ToolArgumentsSchema.closest("content", List.of("newcontent", "path")));
        assertEquals("Content", ToolArgumentsSchema.closest("content", List.of("Content", "x")), "忽略大小写");
        assertEquals("contnt", ToolArgumentsSchema.closest("content", List.of("contnt")), "编辑距离 1");
        assertEquals("oldcontent", ToolArgumentsSchema.closest("content", List.of("path", "oldcontent")),
                "oldcontent 后缀差 3(old)恰达上界 → 命中;path 无近似");
        assertNull(ToolArgumentsSchema.closest("content", List.of("path", "workspace")), "全部无近似 → null");
        assertNull(ToolArgumentsSchema.closest("content", List.of()), "无候选 → null");
    }

    @Test
    void editDistanceIsStandard() {
        assertEquals(0, ToolArgumentsSchema.editDistance("abc", "abc"));
        assertEquals(1, ToolArgumentsSchema.editDistance("abc", "abx"));
        assertEquals(1, ToolArgumentsSchema.editDistance("content", "contnt"), "删一个 e");
        assertEquals(2, ToolArgumentsSchema.editDistance("content", "cotnent"), "两位互换 = 两次替换");
        assertEquals(3, ToolArgumentsSchema.editDistance("", "abc"));
    }
}
