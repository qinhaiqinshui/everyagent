package dev.everyagent.worker.task;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LoopRepeatGuard 纯单测(无 Spring):
 * 签名顺序无关性、无工具调用归零、阈值触发与收口重置、参数变化重置、阈值 ≤0 关闭。
 */
class LoopRepeatGuardTest {

    private static AssistantMessage.ToolCall call(String id, String name, String args) {
        return new AssistantMessage.ToolCall(id, "function", name, args);
    }

    @Test
    void signatureIsOrderInsensitive() {
        var a = List.of(call("1", "read", "{\"p\":1}"), call("2", "grep", "{\"q\":2}"));
        var b = List.of(call("3", "grep", "{\"q\":2}"), call("4", "read", "{\"p\":1}"));
        assertEquals(LoopRepeatGuard.signature(a), LoopRepeatGuard.signature(b));
    }

    @Test
    void nullSignatureWhenNoToolCalls() {
        assertNull(LoopRepeatGuard.signature(List.of()));
        assertNull(LoopRepeatGuard.signature(null));
    }

    @Test
    void triggersAfterConsecutiveIdenticalRounds() {
        LoopRepeatGuard g = new LoopRepeatGuard(3);
        var round = List.of(call("1", "read", "{\"p\":1}"));
        assertNull(g.check(round)); // 第 1 次(基线)
        assertNull(g.check(round)); // 重复 1
        assertNull(g.check(round)); // 重复 2
        String stop = g.check(round); // 重复 3 → 收口
        assertNotNull(stop);
        assertTrue(stop.contains("疑似死循环"));
        // 收口即重置:续跑从第一次出现重新计数
        assertNull(g.check(round));
        assertNull(g.check(round));
        assertNull(g.check(round));
        assertNotNull(g.check(round));
    }

    @Test
    void differentArgumentsResetCounter() {
        LoopRepeatGuard g = new LoopRepeatGuard(2);
        assertNull(g.check(List.of(call("1", "read", "{\"p\":1}"))));
        assertNull(g.check(List.of(call("2", "read", "{\"p\":1}")))); // 重复 1
        assertNull(g.check(List.of(call("3", "read", "{\"p\":2}")))); // 参数变化 → 重置
        assertNull(g.check(List.of(call("4", "read", "{\"p\":2}")))); // 重复 1
        assertNotNull(g.check(List.of(call("5", "read", "{\"p\":2}")))); // 重复 2 → 收口
    }

    @Test
    void emptyRoundResetsCounter() {
        LoopRepeatGuard g = new LoopRepeatGuard(2);
        var round = List.of(call("1", "read", "{}"));
        assertNull(g.check(round));
        assertNull(g.check(round)); // 重复 1
        assertNull(g.check(List.of())); // 纯文本轮 → 归零
        assertNull(g.check(round)); // 重新基线
        assertNull(g.check(round)); // 重复 1
        assertNotNull(g.check(round)); // 重复 2 → 收口
    }

    @Test
    void disabledWhenThresholdNotPositive() {
        LoopRepeatGuard g = new LoopRepeatGuard(0);
        var round = List.of(call("1", "read", "{}"));
        for (int i = 0; i < 10; i++) {
            assertNull(g.check(round));
        }
    }
}
