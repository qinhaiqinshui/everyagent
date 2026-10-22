package dev.everyagent.plugin.api.spi;

import dev.everyagent.plugin.api.execution.ExecContext;

/**
 * 文本文件写事件监听器 SPI —— worker 文件工具(update_file/create_file)写盘成功后回调。
 *
 * <p>回调点在 {@code fs.writeText} 落盘之后、写锁释放之前(<b>锁内回调</b>):同一文件的
 * 并发写按 pathLock 串行,保证<b>回调序=落盘序</b>,消费方按「首条 before + 末条 after」
 * 聚合文件级净状态不会错位(架构 §7.15.2)。
 *
 * <p>携带的是<b>文件级全文快照</b>(写前/写后),不是工具入参片段——update_file 的
 * oldcontent/content 只是被替换的局部片段,消费方(如 file-change 插件的轮末 diff)
 * 必须以本回调的全文为准。失败调用(文件已存在/oldcontent 不唯一/授权拒绝等)不回调。
 *
 * <p>实现方不得抛异常影响工具执行——FsToolSupport 对单家异常隔离记 warn,但实现仍应
 * 自行兜底(只做内存聚合,不做 IO)。回调发生在工具执行线程,须快进快出。
 */
public interface FileWriteListener {

    /** 工具名:update_file(原位替换,携带写前/写后全文)。 */
    String TOOL_UPDATE_FILE = "update_file";
    /** 工具名:create_file(新建文件,写前不存在,before 为 null)。 */
    String TOOL_CREATE_FILE = "create_file";

    /**
     * 文件写盘成功回调。
     *
     * @param exec 任务执行上下文(subjectId 定位任务/terminal 判收口)
     * @param agentId 发起写的 agent Id(主/子)
     * @param tool 工具名({@link #TOOL_UPDATE_FILE} / {@link #TOOL_CREATE_FILE})
     * @param path 工具原始 path 参数(相对任务工作区根,或工作区外授权绝对路径)
     * @param before 写前<b>全文</b>;create_file(文件此前不存在)为 null
     * @param after 写后<b>全文</b>(磁盘真实内容,含其本来的行尾)
     */
    void onFileWritten(ExecContext exec, String agentId, String tool, String path,
            String before, String after);
}
