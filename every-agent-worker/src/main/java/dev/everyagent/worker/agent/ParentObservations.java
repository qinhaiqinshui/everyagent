package dev.everyagent.worker.agent;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;

/**
 * observation parent 装配的唯一 internal 包装(架构 §7.14.3 internal 依赖策略)。
 *
 * <p>照抄自 Spring AI 2.0.1 {@code DefaultToolCallingManager.executeToolCall}:取
 * reactive context 里的 {@code micrometer.observation} 作 parent,缺省退 registry
 * 当前 observation。<b>不用反射</b>——{@link ToolCallReactiveContextHolder} 位于框架
 * internal 包,照抄 import 使其成为编译期依赖:框架后续版本移动/删除该类时这里立即
 * 编译红灯显性可见,再按当时源码对齐。
 *
 * <p><b>当前不预置 parent 装配</b>(返回 null):全仓零 {@link ObservationRegistry}
 * 消费、零 tracing 配置,NOOP registry 下 {@code getCurrentObservation()} 恒 null,
 * {@code ToolCallReactiveContextHolder.getContext()} 恒空 context——装配即空转。
 * 待接 trace 时按需启用(启用 = 还原方法体为照抄逻辑;选型见决策:tracing UI span
 * 树嵌套 → 评估 internal 包装或当时版本的官方 API;只要日志/事件调用树关联 →
 * ctx 透传 {@code traceparent}(hub {@code ext.traceparent} 已有 W3C 约定 §5.6),
 * 零框架依赖)。调用点在 {@code PerCallToolCallingManager.frameworkToolCall},
 * 注释同步保留。
 */
final class ParentObservations {

    private ParentObservations() {
    }

    /**
     * 照抄逻辑(当前空转,接 trace 时启用):
     * <pre>{@code
     * return ToolCallReactiveContextHolder.getContext()
     *         .getOrDefault("micrometer.observation", registry.getCurrentObservation());
     * }</pre>
     */
    static Observation reactiveContextOrNull(ObservationRegistry registry) {
        return null;
    }
}
