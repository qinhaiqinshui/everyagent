package dev.everyagent.plugin.sandbox.codex.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.util.Arrays;

/**
 * 长度前缀 JSON 帧编解码（设计文档 §2.7，对齐 codex framed_io.rs）。
 *
 * <p>帧格式：4 字节小端 u32 长度前缀 + UTF-8 JSON 载荷，单帧 ≤{@value #MAX_FRAME_LEN}
 * 字节（8 MiB，为不可信对端的单帧内存设上界）。JSON 载荷结构由 {@link IpcJson} 负责。
 *
 * <p>管道 I/O：{@link #readFrame(WinNT.HANDLE)} 用 {@code Kernel32.ReadFile} 分批读满
 * （首读即 EOF/broken-pipe 返回 null 表示对端正常关闭，帧中间断开抛异常）；
 * {@link #writeFrame(WinNT.HANDLE, IpcMessage)} 用 {@code WriteFile} 分块写满并
 * 处理部分写（管道 WriteFile 可能在只消费部分缓冲后返回成功）。纯编解码部分
 * （{@link #encodeFrame}/{@link #decodeFrame(byte[])}）不碰 Win32，可跨平台单测。
 */
public final class FrameCodec {

    /** 单帧上界（codex MAX_FRAME_LEN = 8 * 1024 * 1024）。 */
    public static final int MAX_FRAME_LEN = 8 * 1024 * 1024;

    /** 长度前缀字节数。 */
    static final int LEN_PREFIX = 4;

    /** 管道单次读/写字节数（分批，防一次性 8MiB 缓冲）。 */
    private static final int IO_CHUNK = 64 * 1024;

    /** ERROR_BROKEN_PIPE(109)/ERROR_NO_DATA(232)：对端写端已关闭。 */
    private static final int ERROR_BROKEN_PIPE = 109;
    private static final int ERROR_NO_DATA = 232;

    private FrameCodec() {
    }

    /** 帧 = {version, message}（对齐 codex FramedMessage；version 由 runner 侧校验）。 */
    public record FramedMessage(int version, IpcMessage message) {
    }

    /** 帧协议错误（超限/截断/坏 JSON/未知消息类型）。 */
    public static final class FrameException extends RuntimeException {
        public FrameException(String message) {
            super(message);
        }

        public FrameException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ---- 纯编解码（跨平台可单测） ----

    /** 消息 → 完整帧字节（长度前缀 + JSON）。超限抛 {@link FrameException}。 */
    public static byte[] encodeFrame(IpcMessage message) {
        byte[] payload = encodePayload(message);
        byte[] frame = new byte[LEN_PREFIX + payload.length];
        putLenPrefix(frame, payload.length);
        System.arraycopy(payload, 0, frame, LEN_PREFIX, payload.length);
        return frame;
    }

    /** 消息 → JSON 载荷字节（无前缀）。超限抛 {@link FrameException}（"frame too large"）。 */
    static byte[] encodePayload(IpcMessage message) {
        byte[] payload;
        try {
            payload = IpcJson.toJson(message).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new FrameException("IPC message serialize failed", e);
        }
        if (payload.length > MAX_FRAME_LEN) {
            throw new FrameException("frame too large: " + payload.length);
        }
        return payload;
    }

    /** 完整帧字节 → {@link FramedMessage}（校验前缀与实际长度一致、不超限）。 */
    public static FramedMessage decodeFrame(byte[] frame) {
        if (frame.length < LEN_PREFIX) {
            throw new FrameException("truncated frame prefix: " + frame.length);
        }
        int len = readLenPrefix(frame);
        if (len > MAX_FRAME_LEN) {
            throw new FrameException("frame too large: " + len);
        }
        if (frame.length != LEN_PREFIX + len) {
            throw new FrameException("frame length mismatch: prefix=" + len
                    + " actual=" + (frame.length - LEN_PREFIX));
        }
        JsonNode root;
        try {
            root = IpcJson.mapper().readTree(frame, LEN_PREFIX, len);
        } catch (java.io.IOException e) {
            throw new FrameException("IPC frame JSON parse failed", e);
        }
        return new FramedMessage(root.path("version").asInt(-1), IpcJson.fromJson(root));
    }

    static void putLenPrefix(byte[] target, int len) {
        target[0] = (byte) (len & 0xFF);
        target[1] = (byte) ((len >>> 8) & 0xFF);
        target[2] = (byte) ((len >>> 16) & 0xFF);
        target[3] = (byte) ((len >>> 24) & 0xFF);
    }

    static int readLenPrefix(byte[] source) {
        return (source[0] & 0xFF) | ((source[1] & 0xFF) << 8)
                | ((source[2] & 0xFF) << 16) | ((source[3] & 0xFF) << 24);
    }

    // ---- 命名管道句柄 I/O（Windows 运行时） ----

    /**
     * 从管道句柄读一帧；两帧之间对端关闭（首读 EOF/broken-pipe）返回 null。
     * 帧中间断开抛 {@link FrameException}。
     */
    public static FramedMessage readFrame(WinNT.HANDLE pipe) {
        byte[] header = readExact(pipe, LEN_PREFIX, true);
        if (header == null) {
            return null;
        }
        int len = readLenPrefix(header);
        if (len > MAX_FRAME_LEN) {
            throw new FrameException("frame too large: " + len);
        }
        byte[] payload = readExact(pipe, len, false);
        JsonNode root;
        try {
            root = IpcJson.mapper().readTree(payload);
        } catch (java.io.IOException e) {
            throw new FrameException("IPC frame JSON parse failed", e);
        }
        return new FramedMessage(root.path("version").asInt(-1), IpcJson.fromJson(root));
    }

    /** 把一帧完整写入管道（分块 + 部分写推进循环，写满为止）。 */
    public static void writeFrame(WinNT.HANDLE pipe, IpcMessage message) {
        byte[] payload = encodePayload(message);
        byte[] header = new byte[LEN_PREFIX];
        putLenPrefix(header, payload.length);
        writeAll(pipe, header);
        writeAll(pipe, payload);
    }

    /**
     * 分批读满 n 字节；{@code eofAtStart=true} 且一字节未读即遇 EOF/broken-pipe
     * 时返回 null（对端在帧边界正常关闭）。
     */
    private static byte[] readExact(WinNT.HANDLE pipe, int n, boolean eofAtStart) {
        byte[] out = new byte[n];
        int done = 0;
        while (done < n) {
            int want = Math.min(n - done, IO_CHUNK);
            byte[] chunk = new byte[want];
            IntByReference read = new IntByReference();
            if (!Kernel32.INSTANCE.ReadFile(pipe, chunk, want, read, null)) {
                int err = Kernel32.INSTANCE.GetLastError();
                if ((err == ERROR_BROKEN_PIPE || err == ERROR_NO_DATA)
                        && done == 0 && eofAtStart) {
                    return null;
                }
                if (err == ERROR_BROKEN_PIPE || err == ERROR_NO_DATA) {
                    throw new FrameException("pipe closed mid-frame after " + done + " bytes");
                }
                throw new Win32Exception("ReadFile(pipe)", err);
            }
            int r = read.getValue();
            if (r == 0) {
                if (done == 0 && eofAtStart) {
                    return null;
                }
                throw new FrameException("pipe EOF mid-frame after " + done + " bytes");
            }
            System.arraycopy(chunk, 0, out, done, r);
            done += r;
        }
        return out;
    }

    /** 分块写满；WriteFile 对管道可能部分成功，循环推进直到全部写完。 */
    private static void writeAll(WinNT.HANDLE pipe, byte[] data) {
        int off = 0;
        while (off < data.length) {
            int len = Math.min(data.length - off, IO_CHUNK);
            byte[] chunk = off == 0 && len == data.length ? data : Arrays.copyOfRange(data, off, off + len);
            IntByReference written = new IntByReference();
            if (!Kernel32.INSTANCE.WriteFile(pipe, chunk, len, written, null)) {
                throw Win32Exception.of("WriteFile(pipe)");
            }
            int w = written.getValue();
            if (w <= 0) {
                throw new FrameException("WriteFile(pipe) made no progress at offset " + off);
            }
            off += w;
        }
    }
}
