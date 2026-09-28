package dev.everyagent.plugin.modellengthguard;

/**
 * 输出预算耗尽（等价 finish_reason=length）。非重试，由任务层统一 error 收口。
 *
 * <p>从 {@link ModelLengthGuardAdvisor} 内部类提取为独立 public 类，
 * 供跨模块引用（任务层收口、测试断言等）。
 */
public final class ModelLengthExhaustedException extends RuntimeException {
    public ModelLengthExhaustedException(String message) {
        super(message);
    }
}
