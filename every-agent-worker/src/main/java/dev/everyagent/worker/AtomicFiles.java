package dev.everyagent.worker;

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
 * <p>约定:调用方负责先写好 tmp(内容已 flush 到 OS),本类只负责 move + 失败清理;
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
}