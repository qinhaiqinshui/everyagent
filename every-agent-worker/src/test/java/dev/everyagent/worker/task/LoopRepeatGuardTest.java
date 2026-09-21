package dev.everyagent.worker.task;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * LoopRepeatGuard 纯单测(无 Spring):
 * 签名顺序无关性、无工具调用归零、阈值触发 WARN(回传提醒)→ 再重复 STOP(终止)、
 * 参数变化重置、纠正后 WARN 标记清除、阈值 ≤0 关闭。
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
    void warnsThenStopsAfterConsecutiveIdenticalRounds() {
        LoopRepeatGuard g = new LoopRepeatGuard(3);
        var round = List.of(call("1", "read", "{\"p\":1}"));
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(round)); // 基线
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(round)); // 重复 1
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(round)); // 重复 2
        assertEquals(LoopRepeatGuard.Action.WARN, g.check(round));    // 重复 3 → 回传提醒(不执行)
        assertEquals(LoopRepeatGuard.Action.STOP, g.check(round));    // 仍重复 → 终止
        // STOP 即重置:续跑从第一次出现重新计数
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(round));
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(round));
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(round));
        assertEquals(LoopRepeatGuard.Action.WARN, g.check(round));
        assertEquals(LoopRepeatGuard.Action.STOP, g.check(round));
    }

    @Test
    void correctionAfterWarnClearsWarnedFlag() {
        // 提醒后 AI 改变了调用(签名变化):warned 标记应清除,重新计数,正常执行
        LoopRepeatGuard g = new LoopRepeatGuard(2);
        var a = List.of(call("1", "read", "{\"p\":1}"));
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(a)); // 基线
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(a)); // 重复 1
        assertEquals(LoopRepeatGuard.Action.WARN, g.check(a));     // 重复 2 → 提醒(warned=true)
        // AI 纠正:换了参数 → 新签名,重置 warned,正常执行
        var b = List.of(call("2", "read", "{\"p\":2}"));
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(b)); // 新基线
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(b)); // 重复 1
        assertEquals(LoopRepeatGuard.Action.WARN, g.check(b));     // 重复 2 → 再次提醒
    }

    @Test
    void differentArgumentsResetCounter() {
        LoopRepeatGuard g = new LoopRepeatGuard(2);
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(List.of(call("1", "read", "{\"p\":1}"))));
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(List.of(call("2", "read", "{\"p\":1}")))); // 重复 1
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(List.of(call("3", "read", "{\"p\":2}")))); // 参数变化 → 重置
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(List.of(call("4", "read", "{\"p\":2}")))); // 重复 1
        assertEquals(LoopRepeatGuard.Action.WARN, g.check(List.of(call("5", "read", "{\"p\":2}"))));    // 重复 2 → 提醒
    }

    @Test
    void emptyRoundResetsCounter() {
        LoopRepeatGuard g = new LoopRepeatGuard(2);
        var round = List.of(call("1", "read", "{}"));
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(round));
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(round)); // 重复 1
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(List.of())); // 纯文本轮 → 归零
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(round));     // 重新基线
        assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(round));     // 重复 1
        assertEquals(LoopRepeatGuard.Action.WARN, g.check(round));        // 重复 2 → 提醒
    }

    @Test
    void disabledWhenThresholdNotPositive() {
        LoopRepeatGuard g = new LoopRepeatGuard(0);
        var round = List.of(call("1", "read", "{}"));
        for (int i = 0; i < 10; i++) {
            assertEquals(LoopRepeatGuard.Action.EXECUTE, g.check(round));
        }
    }
}

