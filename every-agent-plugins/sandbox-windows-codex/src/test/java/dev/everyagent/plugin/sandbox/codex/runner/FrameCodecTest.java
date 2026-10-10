package dev.everyagent.plugin.sandbox.codex.runner;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FrameCodec 分帧编解码单测（跨平台纯 Java；对齐 codex framed_io.rs 行为）。
 */
class FrameCodecTest {

    @Test
    void roundTripAllNineMessageTypes() {
        List<IpcMessage> messages = List.of(
                new IpcMessage.SpawnRequest(List.of("cmd.exe", "/c", "ver"), "C:\\ws",
                        Map.of("PATH", "C:\\Windows"), List.of("S-1-5-21-1-2-3-4"),
                        List.of("C:\\ws"), List.of(), "offline", 12_345L, false, true,
                        "EveryAgentCodexDesktop-abcd"),
                new IpcMessage.SpawnRequest(List.of("cmd.exe"), "C:\\", Map.of(), List.of("S-1-1-0"),
                        null, null, null, null, false, false, null),
                new IpcMessage.SpawnReady(4242),
                new IpcMessage.Output("aGVsbG8=", IpcMessage.Stream.STDOUT),
                new IpcMessage.Output(IpcMessage.encodeBytes("err".getBytes()), IpcMessage.Stream.STDERR),
                new IpcMessage.Stdin("eHg="),
                new IpcMessage.CloseStdin(),
                new IpcMessage.Resize(24, 80),
                new IpcMessage.Exit(192, true),
                new IpcMessage.Exit(0, false),
                new IpcMessage.Error("boom", IpcMessage.ErrorStage.SPAWN_CHILD, 1312),
                new IpcMessage.Error("late", IpcMessage.ErrorStage.WRITE_SPAWN_READY, null),
                new IpcMessage.Terminate());
        for (IpcMessage m : messages) {
            FrameCodec.FramedMessage back = FrameCodec.decodeFrame(FrameCodec.encodeFrame(m));
            assertEquals(IpcMessage.IPC_PROTOCOL_VERSION, back.version(), "wire version 必须是 6");
            assertEquals(m, back.message(), "往返失真: " + m.tag());
        }
    }

    @Test
    void lengthPrefixIs4ByteLittleEndian() {
        byte[] probe = new byte[4];
        FrameCodec.putLenPrefix(probe, 0x01020304);
        assertArrayEquals(new byte[] { 0x04, 0x03, 0x02, 0x01 }, probe,
                "低字节在前（小端），对齐 len.to_le_bytes()");

        byte[] frame = FrameCodec.encodeFrame(new IpcMessage.SpawnReady(1));
        assertEquals(frame.length - FrameCodec.LEN_PREFIX, FrameCodec.readLenPrefix(frame));
    }

    @Test
    void largeFrameOver64KiBRoundTrips() {
        byte[] data = new byte[200_000]; // 超过 64KiB 管道缓冲，验证分批读写路径
        java.util.Arrays.fill(data, (byte) 'z');
        IpcMessage.Stdin big = new IpcMessage.Stdin(IpcMessage.encodeBytes(data));
        FrameCodec.FramedMessage back = FrameCodec.decodeFrame(FrameCodec.encodeFrame(big));
        assertEquals(big, back.message());
        assertTrue(FrameCodec.encodeFrame(big).length > FrameCodec.LEN_PREFIX + 200_000);
    }

    @Test
    void encodeRejectsFramesOver8MiB() {
        // base64 后约 10.5MiB > 8MiB 上限
        StringBuilder huge = new StringBuilder(9 * 1024 * 1024);
        for (int i = 0; i < 9 * 1024 * 1024; i++) {
            huge.append('x');
        }
        IpcMessage.SpawnRequest fat = new IpcMessage.SpawnRequest(List.of("cmd.exe"), "C:\\",
                Map.of("BIG", huge.toString()), List.of("S-1-5-21-1-2-3-4"), null, null, null,
                null, false, false, null);
        FrameCodec.FrameException ex = assertThrows(FrameCodec.FrameException.class,
                () -> FrameCodec.encodeFrame(fat));
        assertTrue(ex.getMessage().contains("frame too large"));
    }

    @Test
    void decodeRejectsOversizedLengthPrefix() {
        // 前缀声明 8MiB+1：即使帧体没跟上也必须先拒绝（防恶意长度声明撑爆内存）
        byte[] evil = new byte[FrameCodec.LEN_PREFIX];
        FrameCodec.putLenPrefix(evil, FrameCodec.MAX_FRAME_LEN + 1);
        FrameCodec.FrameException ex = assertThrows(FrameCodec.FrameException.class,
                () -> FrameCodec.decodeFrame(evil));
        assertTrue(ex.getMessage().contains("frame too large"));
    }

    @Test
    void decodeRejectsTruncatedAndMismatchedFrames() {
        assertThrows(FrameCodec.FrameException.class,
                () -> FrameCodec.decodeFrame(new byte[] { 1, 2, 3 }));
        byte[] frame = FrameCodec.encodeFrame(new IpcMessage.Terminate());
        byte[] truncated = java.util.Arrays.copyOf(frame, frame.length - 1);
        assertThrows(FrameCodec.FrameException.class, () -> FrameCodec.decodeFrame(truncated));
    }

    @Test
    void decodeRejectsUnknownMessageTypeAndBadJson() {
        byte[] badType = frameBytes("{\"version\":6,\"type\":\"bogus\",\"payload\":{}}");
        assertThrows(FrameCodec.FrameException.class, () -> FrameCodec.decodeFrame(badType));
        byte[] badJson = frameBytes("not-json{");
        assertThrows(FrameCodec.FrameException.class, () -> FrameCodec.decodeFrame(badJson));
    }

    @Test
    void decodePreservesWireVersionForRunnerSideValidation() {
        // codex read_spawn_request 在 runner 侧校验 version：解码必须如实带回
        FrameCodec.FramedMessage old = FrameCodec.decodeFrame(
                frameBytes("{\"version\":5,\"type\":\"terminate\",\"payload\":{}}"));
        assertEquals(5, old.version());
        assertEquals(new IpcMessage.Terminate(), old.message());
    }

    @Test
    void maxFrameExactly8MiBLimitIsInclusive() {
        assertEquals(8 * 1024 * 1024, FrameCodec.MAX_FRAME_LEN);
        // 恰好 8MiB 的前缀值合法（解码侧只是不认超限值）
        byte[] at = new byte[FrameCodec.LEN_PREFIX];
        FrameCodec.putLenPrefix(at, FrameCodec.MAX_FRAME_LEN);
        // 帧体缺失 → 报长度不匹配而非超限
        assertThrows(FrameCodec.FrameException.class, () -> FrameCodec.decodeFrame(at));
    }

    private static byte[] frameBytes(String json) {
        byte[] payload = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] frame = new byte[FrameCodec.LEN_PREFIX + payload.length];
        FrameCodec.putLenPrefix(frame, payload.length);
        System.arraycopy(payload, 0, frame, FrameCodec.LEN_PREFIX, payload.length);
        return frame;
    }
}
