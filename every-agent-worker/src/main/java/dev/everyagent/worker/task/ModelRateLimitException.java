package dev.everyagent.worker.task;

/**
 * 模型请求限流超时/排队已满错误(docs/design-model-rate-limit.md §7.2)。
 *
 * <p>语义:前置限流器在「队列满 + 等待超时」时抛出,提示用户减少并发/调大配置。
 * 非重试:不是 OpenAIServiceException(429/5xx)/IO/超时/重试标记,外层
 * {@link TransientErrorRetryAdvisor#isRetryable} 不会命中 → 不会被退避重试风暴放大。
 * 经任务层 error 事件收口,文本给足操作建议。
 */
public class ModelRateLimitException extends RuntimeException {

    public ModelRateLimitException(String message) {
        super(message);
    }
}