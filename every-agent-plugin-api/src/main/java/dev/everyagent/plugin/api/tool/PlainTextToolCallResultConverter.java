package dev.everyagent.plugin.api.tool;

import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolCallResultConverter;

import java.lang.reflect.Type;

/**
 * 工具结果「文本原样透传」转换器: {@code String} 结果不做 JSON 编码。
 *
 * <p><b>为什么需要它（2026-12 定案）</b>：Spring AI 的
 * {@link DefaultToolCallResultConverter} 对<b>所有</b>工具结果一律
 * {@code JsonHelper.toJson(result)}——返回 {@code String} 的工具（shell / 文件读写 /
 * 子 agent / ask_user 等<b>文本类</b>工具）因此被 JSON 字符串化:外层加一对 {@code "}、
 * 内部换行变字面 {@code \r\n}、引号变 {@code \"}、反斜杠变 {@code \\}。对模型而言这是
 * 纯损耗:多行输出压成一行转义串，可读性显著下降，且与 {@link dev.everyagent.plugin.api.shell.ExecResults}
 * 约定的「stdout 数据段 + 可选 {@code [stderr]} 段 + exit code 尾注」纯文本形态、
 * 以及 {@code read_file} 描述里承诺的「直接返回文件文本内容(纯文本)」自相矛盾。
 *
 * <p>实测证据（两处独立确认）:
 * <ol>
 *   <li>单元级: {@code FunctionToolCallback} 与 {@code ToolCallbacks.from}({@code MethodToolCallback})
 *       两条注册路径,返回 {@code "行一\n行二"} 的工具结果都被转成 {@code "\"行一\\n行二\""};</li>
 *   <li>运行级: 产品自身事件日志里 {@code tool.result.summary} 直接就是
 *       {@code "\"# ISSUES.md …\n\n…\""} 这种字面转义串（该值就是回灌模型与落盘的
 *       同一份 {@code ToolResponseMessage.responseData()}）。</li>
 * </ol>
 *
 * <p><b>只作用于 {@code String}</b>:非文本结果（如返回 {@code Map}/POJO 的工具）仍交回
 * {@link DefaultToolCallResultConverter}，保持「结构化结果 → JSON 对象」的框架语义不变
 * （那类结果的 {@code {…}} 形态本就是模型要读的 JSON，不能再套一层引号）。
 *
 * <p>无状态、线程安全;} 挂载点两处:
 * <ul>
 *   <li>编程式 {@code FunctionToolCallback}: {@code builder.toolCallResultConverter(new PlainTextToolCallResultConverter())}；</li>
 *   <li>注解式 {@code @Tool(resultConverter = PlainTextToolCallResultConverter.class)}
 *       （该注解默认值即 {@code DefaultToolCallResultConverter.class}，显式覆盖即可）。</li>
 * </ul>
 */
public final class PlainTextToolCallResultConverter implements ToolCallResultConverter {

    /** 非文本结果回退到框架默认转换器（保持原有 JSON 语义）。 */
    private static final ToolCallResultConverter NON_TEXT_FALLBACK =
            new DefaultToolCallResultConverter();

    @Override
    public String convert(Object result, Type returnType) {
        if (result instanceof String text) {
            return text;
        }
        return NON_TEXT_FALLBACK.convert(result, returnType);
    }
}