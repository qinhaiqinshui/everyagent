package dev.everyagent.worker.task;

/**
 * 异常根因提取(沿 cause 链到底),用于任务失败日志/事件收口。
 *
 * <p>背景:主 agent 的 advisor 链含 4 个 {@code implements BaseAdvisor} 的 advisor
 * (MeasureDuration/SystemInfo/Skill/GitAutoSync),各自用 Spring AI BaseAdvisor 默认
 * {@code adviseStream},其 {@code onErrorResume} 会把任意错误包装成
 * {@code IllegalStateException("Stream processing failed", cause)}——嵌套 4 层后
 * 完整堆栈数百行 reactor operator 帧刷屏,真实错误(message)被埋在最里层。
 * 收口统一取最内层 cause,一行摘要即可定位。
 */
public final class RootCause {

    private RootCause() {
    }

    /** 沿 cause 链取最内层异常(深度封顶 16 防环,忽略自引用)。 */
    public static Throwable of(Throwable t) {
        Throwable c = t;
        for (int depth = 0; c.getCause() != null && c.getCause() != c && depth < 16; depth++) {
            c = c.getCause();
        }
        return c;
    }

    /** 一行摘要:"最内层异常类名: message"(message 为 null/空白时仅类名)。 */
    public static String summary(Throwable t) {
        Throwable root = of(t);
        String m = root.getMessage();
        return root.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }
}
