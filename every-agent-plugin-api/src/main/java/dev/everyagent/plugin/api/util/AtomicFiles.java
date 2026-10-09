package dev.everyagent.plugin.api.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 健壮的原子文件替换(临时文件 → 目标文件)。
 *
 * <p>背景(实测):Windows 上 {@code Files.move(tmp, f, ATOMIC_MOVE, REPLACE_EXISTING)}
 * 在目标文件被防御软件实时扫描/搜索索引/并发读短暂持锁时,会抛出
 * {@code AccessDeniedException}(而非 {@code AtomicMoveNotSupportedException})。
 * 旧实现只降级了后者,导致 rounds.jsonl / meta.json / queue.jsonl 等原子写失败、
 * tmp 残留——任务耗时「测出来却写不进 rounds.jsonl」即由此产生。
 *
 * <p>本类统一处理:
 * <ol>
 *   <li>优先 {@code ATOMIC_MOVE} 原子替换(文件系统不支持 → 降级普通 REPLACE_EXISTING);</li>
 *   <li>其余 {@code IOException}(尤其 Windows {@code AccessDeniedException})做短退避重试,
 *       规避 AV/索引的毫秒级短暂锁;</li>
 *   <li>最终失败清理残留 tmp 后抛出(调用方按各自语义 catch / fire-and-forget)。</li>
 * </ol>
 *
 * <p>约定:{@link #replace} 只负责 move —— 调用方负责先写好 tmp(内容已 flush 到 OS);
 * 或直接用 {@link #writeText}(会自动创建唯一名 tmp、写入、替换并清理),避免各自手搓 tmp 命名。
 * 成功时 tmp 已被 move 走,失败时删除残留,故不会留下 {@code *.tmp} 垃圾。
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /** 替换尝试次数(含首次)。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 相邻重试间隔(毫秒,线性递增 50/100/150)。 */
    private static final long RETRY_BACKOFF_MS = 50;

    /**
     * 原子替换 tmp → target。
     *
     * @param tmp    已写好的临时文件(与 target 同目录,保证同文件系统内 move)
     * @param target 目标文件(不存在则创建;存在则替换)
     * @throws IOException 重试耗尽后仍失败(此时 tmp 已被尽力清理);InterruptedException 转
     *                     re-interrupt 后按当前失败抛出
     */
    public static void replace(Path tmp, Path target) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    // 文件系统不支持原子换:退化为直接替换(仍带 REPLACE_EXISTING)
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return; // 成功:tmp 已被 move 走
            } catch (IOException e) {
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        // 最终失败:清理残留 tmp(尽力而为,清理失败忽略),再向调用方抛出。
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException ignore) {
            // 清理失败不掩盖原始异常
        }
        if (last != null) {
            throw last;
        }
        throw new IOException("原子替换失败(无原始异常): " + tmp + " -> " + target);
    }

    /**
     * 原子写文本:在同目录创建**唯一名**临时文件 → 写入 UTF-8 内容 → {@link #replace} 原子替换 →
     * 清理残留 tmp。**不要用固定名 tmp**(如 {@code meta.json.tmp}):并发写同一目标时,两个写入者会
     * 写同一个 tmp 再各自 move,可能把半截内容替换进目标;唯一名让每个写入者各有独立 tmp,配合原子
     * move 保证「先完成的完整内容」胜出,绝不出现半截。
     *
     * <p>tmp 必须与 target 同目录(同文件系统,ATOMIC_MOVE 才可用),故不接受跨目录目标语义。
     * 目录不存在时抛出(与 {@link Files#createTempFile} 一致);调用方负责先建目录。
     *
     * @param target  目标文件(不存在则创建;存在则整体替换)
     * @param content 完整的新内容(UTF-8)
     * @throws IOException 写入或替换失败(此时 tmp 已被尽力清理,不残留垃圾)
     */
    public static void writeText(Path target, String content) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(),
                "." + target.getFileName() + ".", ".tmp");
        try {
            Files.writeString(tmp, content, java.nio.charset.StandardCharsets.UTF_8);
            replace(tmp, target);
        } finally {
            // 成功时 tmp 已被 move 走(replace 内部);replace 失败时它已清理;此处兜底写失败的情形,
            // 避免唯一名 tmp 变成永不清理的孤儿。
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignore) {
                // 清理失败不掩盖原始异常
            }
        }
    }
}
