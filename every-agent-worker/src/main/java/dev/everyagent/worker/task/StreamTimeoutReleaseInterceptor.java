package dev.everyagent.worker.task;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

/**
 * 流式长思考的 callTimeout 解除拦截器(架构 §7.4.2)。
 *
 * <p>背景:spring-ai 的 {@code OpenAiChatOptions.timeout(t)} 单值在 openai-java 展开为
 * {@code Timeout.request(t)},映射 okhttp {@code callTimeout}(整个调用的总时长上限,含
 * 流式全程);且 {@code AbstractOpenAiOptions.getTimeout()} 永远非 null(未设默认 60s),
 * per-request 四分量每次覆盖 client 级配置。reasoning 模型(reasoningEffort=high)单轮
 * 长思考可达数十分钟,callTimeout 到点 okhttp 强制断流(IOException)——表象与 provider
 * 粗暴断流一致,且断流时输出量=思考速度×上限时长,常低于 maxTokens 的 80%,
 * {@code ModelLengthGuardAdvisor} 比例判定不命中,落入瞬时重试死循环(每次重试重放整段
 * 长思考,再次到点断流,循环几十分钟)。
 *
 * <p>职责(单一):对每个 call 解除 call 级总时长,流式响应只要持续有 chunk 即不限总时长;
 * 同时剥除 {@code X-Stainless-Timeout} 请求头(该头携带 callTimeout 秒数,防 provider
 * 按头掐流)。
 *
 * <p>实现:okhttp 的 call timeout 在 {@code RealCall.execute()} 入口 {@code enter()}
 * 进 okio watchdog 队列,到点 {@code timedOut()→cancel()};{@code chain.call().timeout()}
 * 返回的就是该 {@code okio.AsyncTimeout} 实例——本拦截器内调用其 {@code exit()}
 * (public API)将条目从 watchdog 队列移除,计时解除。{@code RealCall} 结束时的 finally
 * {@code exit()} 对已移除条目幂等(null),不影响收尾;{@code Timeout.clearTimeout()}
 * 只清时长字段、不撤销已调度计时(实测到点仍断),不可用。
 *
 * <p>兜底仍在:静默挂起由 okhttp readTimeout(读间隔上限,openai-java 默认 10 分钟)与
 * {@code ModelLengthGuardAdvisor} 的 stall(120s)先后兜住;真网络断连照常抛 IOException
 * 交瞬时重试。无状态线程安全,单例复用。
 */
public final class StreamTimeoutReleaseInterceptor implements Interceptor {

    /** Stainless SDK 写入的调用超时提示头(秒);服务端可能据此掐流,一并剥除。 */
    static final String STAINLESS_TIMEOUT_HEADER = "X-Stainless-Timeout";

    public static final StreamTimeoutReleaseInterceptor INSTANCE = new StreamTimeoutReleaseInterceptor();

    private StreamTimeoutReleaseInterceptor() {
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        // 解除 call 级总时长上限:从 okio watchdog 队列移除本 call 的计时条目。
        // (Call 接口声明返回 okio.Timeout,exit() 在 AsyncTimeout 上;RealCall 的实例
        // 恒为其 AsyncTimeout 子类,cast 安全。)
        ((okio.AsyncTimeout) chain.call().timeout()).exit();
        Request request = chain.request();
        if (request.header(STAINLESS_TIMEOUT_HEADER) == null) {
            return chain.proceed(request);
        }
        return chain.proceed(request.newBuilder()
                .removeHeader(STAINLESS_TIMEOUT_HEADER)
                .build());
    }
}
