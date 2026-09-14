package dev.everyagent.worker.os.pty;

import java.io.Closeable;
import java.io.IOException;

/**
 * 交互式伪终端(PTY)抽象:worker 用真 PTY 在指定 cwd 拉起交互式 shell,
 * 为「Web 内嵌终端」提供底层 I/O 通道。
 *
 * <p>实现基于 <b>pty4j</b>(JetBrains 开源,IntelliJ IDEA 内置终端同款),
 * 它封装了 Windows ConPTY/WinPTY 与 Unix openpty/posix_openpt,
 * 无需手写 JNA fork/exec——遵循「复用框架禁止重复造轮子」红线。
 *
 * <p>生命周期:由 {@link TerminalPtyFactory#open} 创建,使用完毕后必须调用
 * {@link #close()} 停止 PTY 子进程并回收资源。close 幂等,可安全多次调用。
 *
 * <p>线程安全:read / write / resize / close 不可并发调用同一实例;
 * 典型用法是单读线程 + 单写线程 + 生命周期管理线程调 close。
 */
public interface TerminalPty extends Closeable {

    /**
     * 阻塞读一段 PTY 输出;返回读到的字节(长度可变)。
     * 子进程退出或 PTY 关闭时返回 {@code null}(EOF 语义)。
     *
     * @throws IOException 读取出错(非 EOF)
     */
    byte[] read() throws IOException;

    /**
     * 将数据写入子进程 stdin(对应 PTY master 写端)。
     *
     * @param data 待写入字节;为空数组时为 no-op
     * @throws IOException 写出错或 PTY 已关闭
     */
    void write(byte[] data) throws IOException;

    /**
     * 调整 PTY 尺寸(cols×rows),并向子进程发 SIGWINCH(pty4j 内部处理)。
     *
     * @param cols 列数({@code >= 1})
     * @param rows 行数({@code >= 1})
     * @throws IOException resize 失败或 PTY 已关闭
     */
    void resize(int cols, int rows) throws IOException;

    /** 子进程是否仍在运行。 */
    boolean isAlive();

    /**
     * 幂等关闭:stop PTY 进程、回收资源。
     * 多次调用安全,后续调用为 no-op。
     */
    @Override
    void close() throws IOException;
}
