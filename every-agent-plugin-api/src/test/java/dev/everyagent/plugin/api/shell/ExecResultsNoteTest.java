package dev.everyagent.plugin.api.shell;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ExecResults#appendNote} 的落位规则单测：承载降级/协议告警提示
 * （ISSUES「输出静默丢失」1-5 的统一出口）必须独立成行、不吞原文也不粘连原文。
 */
class ExecResultsNoteTest {

    @Test
    void 空文本直接成为独立行() {
        assertEquals("[降级:文件承载不可用,改用管道承载]\n",
                ExecResults.appendNote("", ExecResults.CARRIER_FALLBACK_NOTE));
    }

    @Test
    void null文本按空串处理() {
        assertEquals("[降级:输出未在宽限内排空,尾部可能缺失]\n",
                ExecResults.appendNote(null, ExecResults.DRAIN_TIMEOUT_NOTE));
    }

    @Test
    void 原文缺尾换行先补再落提示() {
        String note = ExecResults.UNKNOWN_FRAME_NOTE_PREFIX + 2 + " 个未知帧 "
                + java.util.List.of("Foo") + "]";
        assertEquals("真实输出\n" + note + "\n",
                ExecResults.appendNote("真实输出", note));
    }

    @Test
    void 原文已有尾换行不重复() {
        assertEquals("真实输出\n[输出承载异常: boom 已读 0 字节]\n",
                ExecResults.appendNote("真实输出\n",
                        ExecResults.CARRIER_TAIL_FAILURE_PREFIX + "boom" + " 已读 " + 0 + " 字节]"));
    }

    @Test
    void 空白提示原样返回不添行() {
        assertEquals("真实输出", ExecResults.appendNote("真实输出", " "));
        assertEquals("", ExecResults.appendNote("", null));
    }
}
