package dev.everyagent.plugin.ripgrepTool;

import dev.everyagent.plugin.api.spi.ExecResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RgToolProvider} 单测：appliesTo 矩阵（wsl-ubuntu 排除，direct/codex/null 等
 * 宿主侧后端放行）、rg 不可用时不注册、注解注册形态（工具名/schema/纯文本结果）。
 */
class RgToolProviderTest {

    private static final Path WS = Path.of("C:\\ws");

    private RgToolProvider provider(RgResolver.Resolved rg, RgToolsTest.RecordingNativeExec exec) {
        return new RgToolProvider(rg, exec);
    }

    @Test
    void appliesToExcludesWslUbuntuOnly() {
        RgToolsTest.RecordingNativeExec exec = new RgToolsTest.RecordingNativeExec();
        RgToolProvider p = provider(new RgResolver.Resolved(Path.of("rg.exe"), true), exec);
        assertFalse(p.appliesTo(ToolContexts.ctx("wsl-ubuntu", WS)),
                "宿主 rg.exe 读不到 Linux 侧路径，wsl 语境搜索归 wsl 后端自己的工具链");
        assertTrue(p.appliesTo(ToolContexts.ctx("direct", WS)));
        assertTrue(p.appliesTo(ToolContexts.ctx("codex", WS)));
        assertTrue(p.appliesTo(ToolContexts.ctx("windows-mic", WS)));
        assertTrue(p.appliesTo(ToolContexts.ctx(null, WS)), "sandbox 槽位为 null 的极端形态按宿主侧放行");
    }

    @Test
    void unavailableRgYieldsNoTools() {
        RgToolsTest.RecordingNativeExec exec = new RgToolsTest.RecordingNativeExec();
        RgToolProvider p = provider(new RgResolver.Resolved(null, false), exec);
        assertTrue(p.createTools(ToolContexts.ctx("direct", WS)).isEmpty(),
                "rg 三档皆无 → 不注册（注册即谎报 rg 可用）");
        assertEquals("ripgrep-tool", p.pluginId());
    }

    @Test
    void createToolsRegistersRgCallbackWithFullSchema() {
        RgToolsTest.RecordingNativeExec exec = new RgToolsTest.RecordingNativeExec();
        exec.next = new ExecResult("", "", 1, false); // 无匹配
        RgToolProvider p = provider(new RgResolver.Resolved(Path.of("rg.exe"), true), exec);
        List<ToolCallback> tools = p.createTools(ToolContexts.ctx("direct", WS));
        assertEquals(1, tools.size());

        ToolCallback cb = tools.get(0);
        assertEquals("rg", cb.getToolDefinition().name());
        // 描述自报全量：用途 / path 默认工作区根 / 无匹配输出形态 / 优先级建议
        String desc = cb.getToolDefinition().description();
        assertTrue(desc.contains("工作区根"), "描述必须写明 path 省略时默认工作区根");
        assertTrue(desc.contains("[rg] 无匹配"), "描述必须预告无匹配输出形态");
        assertTrue(desc.contains("优先用本工具"), "描述必须建议优先用本工具做内容搜索");

        // schema 字段全集（snake_case 名进 JSON schema）
        String schema = cb.getToolDefinition().inputSchema();
        for (String field : new String[] { "pattern", "path", "glob",
                "case_insensitive", "files_with_matches", "max_results" }) {
            assertTrue(schema.contains("\"" + field + "\""), "schema 缺参数 " + field);
        }

        // 端到端一次：JSON 入参 → 纯文本结果（PlainTextToolCallResultConverter）
        String out = cb.call("{\"pattern\":\"foo\"}");
        assertEquals("[rg] 无匹配\n", out);
        assertEquals(1, exec.spawns);
    }
}
