package dev.everyagent.worker.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelLengthGuardAdvisor} 的「输出≈maxTokens」粗估判定(nearMax)与 CJK 感知 token 估算
 * 的纯函数单测;advisor 的流式合成/超时路径依赖 Reactor,由集成态验证。
 */
class ModelLengthGuardAdvisorTest {

    @Test
    void nearMaxBounds() {
        long max = 65536;
        // 80%~140% 区间内判耗尽
        assertTrue(ModelLengthGuardAdvisor.nearMax(max * 4 / 5, max), "80% 下边界");
        assertTrue(ModelLengthGuardAdvisor.nearMax(max, max), "100%");
        assertTrue(ModelLengthGuardAdvisor.nearMax(max * 7 / 5, max), "140% 上边界");
        // 区间外不判耗尽
        assertFalse(ModelLengthGuardAdvisor.nearMax(max / 2, max), "50% 远未耗尽");
        assertFalse(ModelLengthGuardAdvisor.nearMax(max * 2, max), "200% 估算偏差过大");
        assertFalse(ModelLengthGuardAdvisor.nearMax(0, max), "0 输出");
        assertFalse(ModelLengthGuardAdvisor.nearMax(max, 0), "maxTokens 未知(0)关闭判定");
    }
}
