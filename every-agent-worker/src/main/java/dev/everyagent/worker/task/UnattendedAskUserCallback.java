package dev.everyagent.worker.task;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.util.JacksonUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 无人值守 ask_user 拦截装饰器(任务级开关,实时生效)。
 *
 * <p>本装饰器包装 {@code AskUserTool} 的 {@link ToolCallback},在工具执行瞬间拦截
 * {@code ask_user} 调用:任务级 {@code TaskEntry.unattended}(volatile)为 true 时,
 * 解析 toolInput 中的问题列表,逐题自动选择第一个选项,拼装为
 * 「题干：首选项」格式的作答文本直接回传给 AI——不创建 ask、不挂起虚拟线程、
 * 不发 ask.create/ask.state/ask.resolved;为 false 时原样委托给真实
 * {@code AskUserTool}(正常挂起等待用户作答)。
 *
 * <p>设计要点:
 * <ul>
 *   <li><b>拦截点在工具执行瞬间(call)</b>,而非模型请求前的工具定义过滤:模型仍看得到
 *       ask_user 工具、仍会发起调用,只是调用被短路。与「运行时中途开关即时生效」语义一致——
 *       无需等下一轮模型请求重新组装工具列表。</li>
 *   <li><b>实时性</b>:装饰器持有 {@link TaskEntry} 引用,每次 call 读 volatile 字段;
 *       用户在运行中点胶囊开/关无人值守,下一次 AI 调用 ask_user 时即生效,不重启任务。</li>
 *   <li><b>事件流一致</b>:合成结果作为正常 tool result 经
 *       {@code WorkerToolEventAdvisor#doGetNextInstructionsForToolCallStream} 按 callId
 *       配对发射 tool.result,与真实 ask 被回答后的事件形态完全一致,前端无需特例。</li>
 *   <li><b>子 agent 无注册</b>:子 agent 本就不注册 ask_user(其工具集不含 AskUserTool),
 *       本装饰器对子 agent 无触发点,天然零副作用。</li>
 * </ul>
 *
 * <p>与 {@code SchemaStrippedToolCallback} 同为 ToolCallback 装饰器,但本类按任务状态
 * 条件拦截,非纯透传包装。实例随每次 {@code buildMainAgent} 新建,状态随实例物化隔离。
 */
public class UnattendedAskUserCallback implements ToolCallback {

    private static final ObjectMapper MAPPER = JacksonUtils.getDefaultJsonMapper();

    private final ToolCallback delegate;
    private final TaskEntry task;

    public UnattendedAskUserCallback(ToolCallback delegate, TaskEntry task) {
        this.delegate = delegate;
        this.task = task;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        if (task.unattended) {
            return autoAnswer(toolInput);
        }
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        if (task.unattended) {
            return autoAnswer(toolInput);
        }
        return delegate.call(toolInput, toolContext);
    }

    /**
     * 代替人工作答:解析 toolInput 的 questions 数组,逐题取第一个选项,
     * 拼装为「题干：首选项」格式(与前端 askStore.toAnswerText 的作答格式一致)。
     * 解析失败/无有效问题时回退为通用提示文本。
     */
    private static String autoAnswer(String toolInput) {
        try {
            JsonNode root = MAPPER.readTree(toolInput);
            JsonNode questions = root.path("questions");
            if (!questions.isArray() || questions.isEmpty()) {
                return "未提供任何问题,跳过提问。";
            }
            List<String> lines = new ArrayList<>();
            for (JsonNode q : questions) {
                String question = textOrEmpty(q.path("question"));
                JsonNode options = q.path("options");
                String firstOption = options.isArray() && !options.isEmpty()
                        ? textOrEmpty(options.get(0)) : "";
                if (!question.isEmpty() && !firstOption.isEmpty()) {
                    lines.add(question + "：" + firstOption);
                }
            }
            if (lines.isEmpty()) {
                return "未提供任何有效问题,跳过提问。";
            }
            return String.join("\n", lines);
        } catch (Exception e) {
            return "问题解析失败,跳过提问。";
        }
    }

    /** 容错提取文本节点(对标 AskUserTool 的 LenientCoercing):value 节点取 text,object 节点取 text/label/name/value 字段。 */
    private static String textOrEmpty(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isValueNode()) {
            return node.asText("");
        }
        if (node.isObject()) {
            for (String key : new String[]{"text", "label", "name", "value", "title", "option", "content"}) {
                JsonNode v = node.get(key);
                if (v != null && v.isValueNode()) {
                    return v.asText("");
                }
            }
        }
        return node.toString();
    }
}
