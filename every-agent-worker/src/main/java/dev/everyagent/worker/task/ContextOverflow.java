package dev.everyagent.worker.task;

import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上下文超限诊断日志(public API 报错 "maximum context length is ..." 时的专项打印)。
 *
 * <p>触发条件:异常链上任一层的消息命中上下文超限特征(OpenAI 兼容协议 400 常见文案:
 * "This endpoint's maximum context length is ..." / "context length" / "reduce the length"
 * / "prompt is too long" 等)。命中后打一条 <b>单行</b> ERROR,内容含:
 * <ul>
 *   <li>任务/agent 归属(taskId、agentId、kind);</li>
 *   <li><b>超限的模型配置</b>:请求 options 的 model/baseUrl/maxTokens/temperature/reasoningEffort,
 *       加任务快照的 configId/name/provider/params.maxTokens;</li>
 *   <li>上下文窗口口径:优先读模型配置 params 里的 {@code contextWindowTokens},
 *       未配置/非法时回退 {@link #DEFAULT_CONTEXT_WINDOW_TOKENS}(日志标注「默认」);</li>
 *   <li>会话规模:消息条数、字符数、按 UTF-8 bytes/3 粗估的输入 token(仅诊断参考);</li>
 *   <li>从报错文本 best-effort 解析的 max/requested/textInput/toolInput/output。</li>
 * </ul>
 *
 * <p>挂在 {@link AgentRunner} 报错出口(主/子 agent 共用),不修改任何请求/异常语义,
 * 纯日志副作用,多任务并发安全。apiKey 一律不进日志(与 HTTP 层脱敏纪律一致)。
 */
public final class ContextOverflow {

    private static final Logger log = LoggerFactory.getLogger(ContextOverflow.class);

    /** 模型配置 params 未声明 contextWindowTokens 时采用的默认上下文窗口(与常见 256k 端点一致)。 */
    public static final long DEFAULT_CONTEXT_WINDOW_TOKENS = 256_000;

    /** 上下文超限特征文案(大小写不敏感;命中任一即触发专项日志)。 */
    private static final Pattern[] OVERFLOW_PATTERNS = {
            Pattern.compile("(?i)maximum context length"),
            Pattern.compile("(?i)context length"),
            Pattern.compile("(?i)context[_ -]length[_ -]exceeded"),
            Pattern.compile("(?i)reduce the length"),
            Pattern.compile("(?i)prompt is too long"),
    };

    private ContextOverflow() {
    }

    /** 异常链(深度封顶 20,防环)上任一层消息命中上下文超限特征即视为超限。 */
    public static boolean matches(Throwable t) {
        Throwable c = t;
        for (int depth = 0; c != null && depth < 20; depth++) {
            String m = c.getMessage();
            if (m != null && matches(m)) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
            c = c.getCause();
        }
        return false;
    }

    /** 单条消息是否命中超限特征。 */
    private static boolean matches(String message) {
        for (Pattern p : OVERFLOW_PATTERNS) {
            if (p.matcher(message).find()) {
                return true;
            }
        }
        return false;
    }

    /** 超限命中则打 ERROR 单行诊断(含模型配置与上下文规模);不命中/入参为空静默。 */
    public static void logIfOverflow(AgentEntity a, Throwable t) {
        if (a == null || t == null || !matches(t)) {
            return;
        }
        log.error("上下文超限: {}", describe(a, t));
    }

    /** 组装单行诊断文本(字段缺省以 - / (未配置) / (默认) 标记,不含 apiKey)。 */
    private static String describe(AgentEntity a, Throwable t) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("task=").append(a.task.taskId)
                .append(" agent=").append(a.agentId)
                .append(" kind=").append(a.kind);

        // 超限的模型配置(请求 options 是实际发往服务商的值)
        sb.append(" | model=").append(nz(a.options.getModel()))
                .append(" baseUrl=").append(nz(a.options.getBaseUrl()))
                .append(" maxTokens=").append(a.options.getMaxTokens() == null
                        ? "(未配置)" : a.options.getMaxTokens())
                .append(" temperature=").append(a.options.getTemperature() == null
                        ? "-" : a.options.getTemperature())
                .append(" reasoningEffort=").append(nz(a.options.getReasoningEffort()));
        ModelSnapshot snap = a.task.snapshot;
        sb.append(" | configId=").append(nz(snap.configId()))
                .append(" provider=").append(nz(snap.provider()))
                .append(" params.maxTokens=").append(paramLong(snap.params(), "maxTokens"))
                .append(" contextWindow=").append(contextWindow(snap.params()));

        // 会话规模(message 数 / 字符数 / 按 bytes/3 粗估的输入 token,仅诊断参考)
        long chars = 0;
        long bytes = 0;
        for (Message m : a.conversation) {
            if (m == null) {
                continue;
            }
            long[] cb = new long[2];
            addSegment(m.getText(), cb);
            if (m instanceof AssistantMessage am && am.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    if (tc == null) {
                        continue;
                    }
                    addSegment(tc.name(), cb);
                    addSegment(tc.arguments(), cb);
                }
            }
            if (m instanceof ToolResponseMessage trm && trm.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    if (r == null) {
                        continue;
                    }
                    addSegment(r.name(), cb);
                    addSegment(r.responseData(), cb);
                }
            }
            chars += cb[0];
            bytes += cb[1];
        }
        sb.append(" | conversation.messages=").append(a.conversation.size())
                .append(" chars=").append(chars)
                .append(" estInputTokens(bytes/3)=").append((bytes + 2) / 3);

        // 从报错文本 best-effort 解析的官方口径数值
        sb.append(" | ").append(extractFigures(t));

        // 原始错误一行摘要(最内层类名 + message,与任务收口同口径)
        Throwable root = RootCause.of(t);
        sb.append(" | error=").append(root.getClass().getSimpleName())
                .append(": ").append(nz(root.getMessage()));
        return sb.toString();
    }

    /** 上下文窗口口径:params.contextWindowTokens 优先,缺省回退默认值并标注。 */
    private static String contextWindow(JsonNode params) {
        if (params != null && params.isObject() && params.has("contextWindowTokens")) {
            long v = params.path("contextWindowTokens").asLong(0);
            if (v > 0) {
                return Long.toString(v);
            }
        }
        return DEFAULT_CONTEXT_WINDOW_TOKENS + "(默认)";
    }

    /** 从任务快照 params 读 Long 参数;缺失/非法返回 (未配置)。 */
    private static String paramLong(JsonNode params, String key) {
        if (params != null && params.isObject() && params.has(key)) {
            long v = params.path(key).asLong(Long.MIN_VALUE);
            return v == Long.MIN_VALUE ? "(非法)" : Long.toString(v);
        }
        return "(未配置)";
    }

    /** 取异常链中第一条命中超限特征的消息,解析其中数值(max/requested/input/output),未命中数值填 ?。 */
    private static String extractFigures(Throwable t) {
        String msg = null;
        Throwable c = t;
        for (int depth = 0; c != null && depth < 20; depth++) {
            String m = c.getMessage();
            if (m != null && matches(m)) {
                msg = m;
                break;
            }
            if (c.getCause() == c) {
                break;
            }
            c = c.getCause();
        }
        if (msg == null) {
            return "overflow-figures=n/a";
        }
        return "max=" + group(msg, "maximum context length is (\\d+)")
                + " requested=" + group(msg, "requested about (\\d+) tokens")
                + " textInput=" + group(msg, "(\\d+) of text input")
                + " toolInput=" + group(msg, "(\\d+) of tool input")
                + " output=" + group(msg, "(\\d+) in the output");
    }

    private static String group(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : "?";
    }

    /** 累计一段文本的字符数与 UTF-8 字节数(cb[0]=chars, cb[1]=bytes)。 */
    private static void addSegment(String s, long[] cb) {
        if (s == null || s.isEmpty()) {
            return;
        }
        cb[0] += s.length();
        cb[1] += s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}