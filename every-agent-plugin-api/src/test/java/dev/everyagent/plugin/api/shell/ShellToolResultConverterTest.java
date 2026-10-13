package dev.everyagent.plugin.api.shell;

import dev.everyagent.plugin.api.tool.PlainTextToolCallResultConverter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 工具结果到达模型时的形态契约测试。
 *
 * <p>契约：文本类工具（shell / 文件读写 / 子 agent …）的结果必须以<b>纯文本</b>进入
 * {@code ToolResponseMessage}——多行保持真实换行、无外层引号、无 {@code \n} 转义。
 *
 * <p>反面参照：Spring AI 默认的 {@code DefaultToolCallResultConverter} 对 String 结果
 * 一律 {@code JsonHelper.toJson(result)}，会把结果压成 {@code "行一\n行二"} 转义串；
 * 故所有文本工具必须显式挂 {@link PlainTextToolCallResultConverter}
 * （{@code @Tool(resultConverter = …)} 或
 * {@code FunctionToolCallback.builder().toolCallResultConverter(…)}）。
 */
class ShellToolResultConverterTest {

    @Test
    void shellResultReachesModelAsPlainText() {
        ShellTool tool = ShellTool.powershell("测试用工具描述", (command, shell) -> "行一\n行二");
        ToolCallback callback = tool.callback();
        String out = callback.call("{\"command\":\"x\"}");
        assertEquals("行一\n行二", out,
                "shell 工具结果必须原样纯文本（多行保持真实换行、无外层引号、无 \\n 转义）");
    }

    /** 挂上转换器的 @Tool 方法：结果原样纯文本。 */
    @Test
    void annotatedToolWithConverterIsPlainText() {
        ToolCallback callback = ToolCallbacks.from(new PlainEchoTool())[0];
        String out = callback.call("{}");
        assertEquals("行一\n行二", out, "显式挂转换器的 @Tool 方法结果必须原样纯文本");
    }

    /**
     * 反面参照（框架默认行为）：@Tool 不带 {@code resultConverter} 时，String 结果会被
     * JSON 字符串化。此断言存在是为了在 Spring AI 改变默认行为时立刻暴露，并说明各文本
     * 工具为何必须显式挂转换器——不是冗余配置（缺失即回归为「引号 + \n 转义」形态）。
     */
    @Test
    void annotatedToolWithoutConverterIsJsonEncodedByFrameworkDefault() {
        ToolCallback callback = ToolCallbacks.from(new DefaultConverterEchoTool())[0];
        String out = callback.call("{}");
        assertEquals("\"行一\\n行二\"", out,
                "框架默认转换器会把 String 结果 JSON 字符串化——这正是文本工具必须显式挂 "
                        + "PlainTextToolCallResultConverter 的原因");
    }

    /** 测试用 @Tool 载体：显式挂纯文本转换器。 */
    static class PlainEchoTool {

        @Tool(description = "回显两行文本",
                resultConverter = PlainTextToolCallResultConverter.class)
        public String echo() {
            return "行一\n行二";
        }
    }

    /** 测试用 @Tool 载体：不挂转换器（走框架默认）。 */
    static class DefaultConverterEchoTool {

        @Tool(description = "回显两行文本")
        public String echo() {
            return "行一\n行二";
        }
    }
}