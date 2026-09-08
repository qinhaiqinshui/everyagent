package dev.everyagent.worker.task;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 层 AI 请求原始内容打印拦截器(架构 §5.2:纯日志副作用,不修改任何请求)。
 *
 * <p>挂在真实 OkHttp 请求链上,打印<b>真正发往模型服务商的完整请求体</b>——此时
 * advisor 链已全部执行完毕({@code SkillAdvisor} 注入的渐进式披露 system 索引、
 * slash token 解析、工具回调定义均已在 prompt 中),打印内容与线上报文一致。
 * 响应侧不在 HTTP 层打印(SSE 流式只有原始字节流,聚合后的 ChatResponse 无意义)。
 *
 * <p>防"重复打印"说明:同一 HTTP 请求只经过本拦截器一次(sync/async 两个 client
 * 各自独立注册一个实例,模型层 call/stream 各发起一次 HTTP)。若日志出现多条
 * 相同请求:要么是工具循环的多轮模型调用(正常,每轮一条),要么是 openai-java
 * 内置重试导致同一请求重发——后者已在 {@link ChatModelFactory} 关闭
 * ({@code maxRetries=0}),瞬时错误重试统一由 advisor 层 {@link TransientErrorRetryAdvisor}
 * 负责,避免双层重试叠加与重复日志。日志中的 {@code #n} 为本拦截器实例的请求序号,
 * 便于区分同一 agent 的多轮请求。
 *
 * <p>脱敏:请求头 {@code Authorization} / {@code api-key} 的值一律替换为 {@code ***};
 * 请求体为 OpenAI 兼容协议 JSON,apiKey 不落 body(只在 header),故 body 原样打印。
 *
 * <p>设计纪律:per-agent 实例化(构造时携带 agentId 仅用于日志前缀标识,只读不可变),
 * 多任务并发安全;是否打印由日志级别控制({@code Logger.debug}),
 * 由 {@code logging.level.dev.everyagent.worker.task.HttpRequestLoggingInterceptor=DEBUG}
 * 统一开关。
 */
public class HttpRequestLoggingInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestLoggingInterceptor.class);

    /** 本 agent 的请求(仅用于日志前缀标识)。 */
    private final String agentId;
    /** 本拦截器实例的请求序号(1-based,便于区分同一 agent 的多轮请求)。 */
    private final java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();

    public HttpRequestLoggingInterceptor(String agentId) {
        this.agentId = agentId;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("==== HTTP REQUEST (agentId={}, #{}) ====\n{}",
                    agentId, seq.incrementAndGet(), describe(chain.request()));
        }
        return chain.proceed(chain.request());
    }

    /** 请求描述:方法 + URL + 脱敏请求头 + 完整 body(不截断)。 */
    private static String describe(Request request) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(request.method()).append(' ').append(request.url()).append('\n');

        // 请求头(Authorization / api-key 脱敏,其余原样)。
        sb.append("headers:\n");
        for (int i = 0; i < request.headers().size(); i++) {
            String name = request.headers().name(i);
            String value = request.headers().value(i);
            sb.append("  ").append(name).append(": ").append(redactHeader(name, value)).append('\n');
        }

        // 请求体:完整 JSON(OpenAI 兼容协议,messages 含 system 渐进式披露索引 / tools 等)。
        RequestBody body = request.body();
        if (body == null) {
            sb.append("body: <empty>\n");
        } else {
            sb.append("body:\n").append(readBody(body)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 敏感头脱敏:Authorization 的 Bearer token 与 api-key 值打码,其余原样。 */
    private static String redactHeader(String name, String value) {
        String lower = name.toLowerCase();
        if (lower.equals("authorization")) {
            return value.regionMatches(true, 0, "Bearer ", 0, 7) ? "Bearer ***" : "***";
        }
        if (lower.equals("api-key") || lower.equals("x-api-key")) {
            return "***";
        }
        return value;
    }

    /** 读取请求体文本:writeTo 到独立 Buffer,不消费原始 body(OpenAI SDK 的 JSON body 可重复写)。 */
    private static String readBody(RequestBody body) throws IOException {
        Buffer buffer = new Buffer();
        body.writeTo(buffer);
        return buffer.readString(StandardCharsets.UTF_8);
    }
}
