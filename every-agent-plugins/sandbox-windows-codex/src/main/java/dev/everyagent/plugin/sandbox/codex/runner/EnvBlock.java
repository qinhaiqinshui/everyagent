package dev.everyagent.plugin.sandbox.codex.runner;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 子进程环境块构造（设计文档 §2.6，对齐 codex process.rs::make_env_block）。
 *
 * <p>env map 按 key <b>大小写不敏感</b>排序（同键名大小写差异再按原串 tie-break，
 * 满足 Windows 环境块大小写不敏感且排序确定的要求），逐条写 UTF-16 {@code "K=V\0"}，
 * 末尾再补一个 {@code \0} 终结符；调用方必须同时传
 * {@code CREATE_UNICODE_ENVIRONMENT} 标志。空 map 返回 {@link Pointer#NULL}
 * （CreateProcess 语义：继承父进程环境）。
 */
public final class EnvBlock {

    private EnvBlock() {
    }

    /** 构造 UTF-16LE 环境块；返回的 {@link Memory} 必须存活到 CreateProcess 返回。 */
    public static Pointer makeEnvBlock(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return Pointer.NULL;
        }
        List<Map.Entry<String, String>> items = new ArrayList<>(env.entrySet());
        items.sort(Comparator
                .comparing((Map.Entry<String, String> e) -> e.getKey().toUpperCase())
                .thenComparing(Map.Entry::getKey));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : items) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\0');
        }
        sb.append('\0');
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_16LE);
        Memory mem = new Memory(bytes.length);
        mem.write(0, bytes, 0, bytes.length);
        return mem;
    }
}
