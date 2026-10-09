package dev.everyagent.worker.tools;


import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.exception.NotFoundException;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 文件工具(read_file / create_file / update_file),移植自 novel_agent-n 的
 * src/tools/file/tool.ts。路径相对任务工作区根,经 {@link FsToolSupport} 沙箱化,
 * 不接收工作区参数——工具随任务绑定,天然 jailed 到该任务的工作区根(更安全,不会误逃到别的工作区)。
 *
 * <p>工作区外路径经 {@link PermissionGate} 授权放行(阻塞弹窗,拒绝/超时回灌错误文本)。
 * agentId 为本工具持有者的真实 Id(主 = task.mainAgentId,子 = 子 agent Id),
 * 用于授权弹窗的事件路由。
 */
public class FileTools {

    /** 无参全量读取时的字符上限(约 30k 字符);超过则截断到完整行并附带提示。 */
    static final int READ_FILE_AUTO_TRUNCATE_CHARS = 30_000;

    private final FsToolSupport fs;
    private final ExecContext task;
    private final String agentId;

    public FileTools(FsToolSupport fs, ExecContext task, String agentId) {
        this.fs = fs;
        this.task = task;
        this.agentId = agentId;
    }

    /** 把文本按 \r?\n 切行,丢弃末尾换行切出的空元素,保持行号与视觉一致。 */
    private static List<String> splitLines(String s) {
        List<String> lines = new ArrayList<>(Arrays.asList(s.split("\r\n|\r|\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /** 归一化到 LF:兼容 CRLF / CR / LF 三种行尾。 */
    private static String toLf(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 把已归一化为 LF 的文本转成目标行尾(eol 为 "\r\n" 或 "\n")。 */
    private static String fromLf(String lfText, String eol) {
        return eol.equals("\n") ? lfText : lfText.replace("\n", "\r\n");
    }

    /** 是否为换行字符(\r 或 \n)。 */
    private static boolean isEol(char c) {
        return c == '\r' || c == '\n';
    }

    /** 跳过 i 处的一个换行单元:\r\n 跳 2,孤立 \r / \n 跳 1;返回新的位置。 */
    private static int skipEol(String s, int i) {
        char c = s.charAt(i);
        if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
            return i + 2;
        }
        return i + 1;
    }

    /**
     * 行尾宽容匹配:从 haystack 的 pos 起尝试匹配 needle,成功返回结束坐标(不含),
     * 失败返回 -1。\r\n / \r / \n 相互等价(与 read_file 的 LF 输出闭环)。
     * 换行单元整体消费,不劈开 CRLF。
     *
     * <p>原 findEolAgnostic 在两处均未推进外层游标 h:字符不匹配时 continue outer
     * (已修),以及 hh>=haystack.length() 剩余不足时 continue outer(未修)——两者都会
     * 在 oldcontent 不在字节 0 / needle 超出剩余长度时死循环。此处改为 matchAt + 单遍
     * 扫描,天然无环。
     */
    private static int matchAt(String haystack, String needle, int pos) {
        int hh = pos;
        int nn = 0;
        while (nn < needle.length()) {
            if (hh >= haystack.length()) {
                return -1;
            }
            char hc = haystack.charAt(hh);
            char nc = needle.charAt(nn);
            if (isEol(hc) && isEol(nc)) {
                hh = skipEol(haystack, hh);
                nn = skipEol(needle, nn);
            } else if (hc == nc) {
                hh++;
                nn++;
            } else {
                return -1;
            }
        }
        return hh;
    }

    /**
     * 行尾宽容查找 oldcontent 的首次出现,并确认全局唯一(单次扫描,顺带统计主导行尾)。
     * 返回 [start, end, eolCode],eolCode: 0=LF, 1=CRLF;未找到返回 null;
     * 出现多次(含重叠)抛 IllegalArgumentException("不唯一")。
     *
     * <p>主导行尾统计与匹配扫描**解耦**:匹配起点遍历每个下标(不再跳过换行位置),
     * 因此 oldcontent 以换行开头(或在 CRLF 的 '\n' 处起)也能正确匹配;原实现先
     * {@code continue} 掉换行再匹配,导致这类 oldcontent 永远「未找到旧内容」。
     */
    private static int[] findUniqueEolAgnostic(String haystack, String needle) {
        // 第一遍:统计主导行尾(CRLF 计 crlf,独立 \n 计 lf,孤立 \r 不计入)
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < haystack.length(); i++) {
            char c = haystack.charAt(i);
            if (c == '\r') {
                if (i + 1 < haystack.length() && haystack.charAt(i + 1) == '\n') {
                    crlf++;
                    i++;
                }
            } else if (c == '\n') {
                lf++;
            }
        }
        // 第二遍:逐下标尝试匹配(起点含换行位置),顺带校验唯一
        int firstStart = -1;
        int firstEnd = -1;
        for (int h = 0; h < haystack.length(); h++) {
            int end = matchAt(haystack, needle, h);
            if (end >= 0) {
                if (firstStart < 0) {
                    firstStart = h;
                    firstEnd = end;
                } else {
                    throw new IllegalArgumentException(
                            "oldcontent 在文档中出现多次,不唯一,无法确定替换位置");
                }
            }
        }
        if (firstStart < 0) {
            return null;
        }
        return new int[] {firstStart, firstEnd, crlf > lf ? 1 : 0};
    }

    @Tool(description = "读取文件的内容。支持可选 line_start / line_end 按 1-based 行区间读取(省略则全量返回),"
            + "直接返回文件文本内容(纯文本),不附加行号等元信息包装。"
            + "当未指定 line_start/line_end 且文件内容超过阈值时,自动截断到完整行并返回提示。")
    public String read_file(
            @ToolParam(description = "文件路径(相对任务工作区根)") String path,
            @ToolParam(description = "1-based 起始行(含);省略从首行开始", required = false) Integer line_start,
            @ToolParam(description = "1-based 结束行(含);省略读到末尾", required = false) Integer line_end)
            throws IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        String content = fs.readText(task, agentId, path);
        if (content.isEmpty()) {
            return "";
        }
        List<String> lines = splitLines(content);
        int total = lines.size();
        if (total == 0) {
            return "";
        }

        // 模型未指定行区间且内容过长时,自动截断到完整行(保证不截断半行)并附带提示
        if (line_start == null && line_end == null
                && content.length() > READ_FILE_AUTO_TRUNCATE_CHARS) {
            int acc = 0;   // 已累积字符数(含换行)
            int cutLine = 0; // 已纳入的行数
            for (int i = 0; i < total; i++) {
                int lineLen = lines.get(i).length() + 1; // +1 for \n
                if (acc + lineLen > READ_FILE_AUTO_TRUNCATE_CHARS && i > 0) {
                    break; // 加入本行会超限,且已有至少 1 行 → 停
                }
                acc += lineLen;
                cutLine = i + 1;
            }
            if (cutLine < total) {
                String truncated = String.join("\n", lines.subList(0, cutLine));
                return "[警告] 内容过长，当前只返回前 " + cutLine + " 行，总行数 " + total
                        + "。如果确认需要读取全部行数，请明确传入 line_start、line_end 参数。\n\n"
                        + truncated;
            }
        }

        int start = line_start != null ? line_start : 1;
        if (start < 1) {
            start = 1;
        }
        int end = line_end != null ? line_end : total;
        if (end > total) {
            end = total; // 越界宽容截到末尾
        }
        if (start > total) {
            throw new IllegalArgumentException(
                    "line_start 超出文件范围(文件共 " + total + " 行, line_start=" + line_start + ")");
        }
        if (end < start) {
            throw new IllegalArgumentException(
                    "line_end 不能小于 line_start(文件共 " + total + " 行, line_start=" + line_start
                            + ", line_end=" + line_end + ")");
        }
        return String.join("\n", lines.subList(start - 1, end));
    }

    @Tool(description = "新建文件并写入内容:文件不存在时创建;文件已存在则报错(不覆盖)。"
            + "写入统一为 LF 行尾。")
    public String create_file(
            @ToolParam(description = "文件路径(相对任务工作区根)") String path,
            @ToolParam(description = "要写入文件的新内容") String content) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        String finalContent = content == null ? "" : content;
        if (finalContent.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("内容疑似二进制(含 NUL 字节),不支持文本写入");
        }
        finalContent = toLf(finalContent); // 新文件默认 LF,与 read_file 输出一致
        // 加锁并二次校验存在性:「不存在才创建」在并发下必须原子,否则两个 create_file 会互相覆盖
        ReentrantLock lock = fs.pathLock(path);
        lock.lock();
        try {
            if (fs.exists(task, path)) {
                throw new IllegalArgumentException("文件已存在,如需修改请用 update_file");
            }
            fs.writeText(task, agentId, path, finalContent, false);
        } finally {
            lock.unlock();
        }
        return "已创建并保存到：" + path;
    }

    @Tool(description = "更新已有文件:把文件中唯一出现的 oldcontent 整体原位替换为 content。"
            + "文件不存在、oldcontent 缺失或不唯一时均报错。匹配对行尾(CRLF/LF)宽容,"
            + "插入内容按文件主导行尾转写,源文件其余部分逐字节保留。"
            + "写入为原子替换并做落盘校验,不会因中断留下截断文件。")
    public String update_file(
            @ToolParam(description = "文件路径(相对任务工作区根)") String path,
            @ToolParam(description = "文件中被整体替换为 content 的旧内容,必须在文件中唯一出现") String oldcontent,
            @ToolParam(description = "替换 oldcontent 后写入的新内容") String content) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (!fs.exists(task, path)) {
            throw new IllegalArgumentException("文件不存在,如需新建请用 create_file");
        }
        if (oldcontent == null || oldcontent.isEmpty()) {
            throw new IllegalArgumentException("oldcontent 不能为空");
        }
        // read-modify-write 整体串行:并发写入者(主/派发 agent、并发工具调用)不得各自基于旧快照
        // 写回,否则会丢失更新甚至写回截断内容。
        ReentrantLock lock = fs.pathLock(path);
        lock.lock();
        try {
            // 单次读即覆盖「存在性 + 写前读」:按写授权档(WRITE)一次弹窗;
            // 文件不存在 -> NotFoundException 转 create_file 提示,省去 exists()+readText 双解析
            String existing;
            try {
                existing = fs.readText(task, agentId, path, PermissionGate.Op.WRITE);
            } catch (NotFoundException e) {
                throw new IllegalArgumentException("文件不存在,如需新建请用 create_file");
            }
            if (existing.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("文件疑似二进制(含 NUL 字节),不支持文本更新");
            }
            // 行尾宽容定位唯一 oldcontent(不劈开 CRLF),并顺带得出主导行尾;
            // 源文件其余部分逐字节保留,混合行尾/孤立 CR 不被改写
            int[] span = findUniqueEolAgnostic(existing, oldcontent);
            if (span == null) {
                throw new IllegalArgumentException("未找到旧内容(oldcontent)");
            }
            int start = span[0];
            int end = span[1];
            String eol = span[2] == 1 ? "\r\n" : "\n";
            String newContent = fromLf(toLf(content == null ? "" : content), eol);
            String replaced = existing.substring(0, start) + newContent + existing.substring(end);
            fs.writeText(task, agentId, path, replaced, false);
            return "已更新到：" + path;
        } finally {
            lock.unlock();
        }
    }
}
