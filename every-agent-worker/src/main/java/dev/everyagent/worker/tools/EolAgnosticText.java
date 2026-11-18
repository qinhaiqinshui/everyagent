package dev.everyagent.worker.tools;

/**
 * 行尾(EOL)宽容的文本定位/替换纯函数集:`update_file` 工具与 `fs.replaceRaw` RPC 共用。
 *
 * <p>匹配语义与 read_file 的 LF 输出闭环:模型/前端拿到的都是 LF 文本,而磁盘文件
 * 可能是 CRLF(或混合行尾),故 oldcontent 里的 {@code \n} 与文件里的 {@code \r\n}、
 * 孤立 {@code \r} 相互等价;换行单元整体消费,不劈开 CRLF。替换区间之外逐字节保留,
 * 插入内容按文件<b>主导行尾</b>转写(混合行尾/孤立 CR 不被改写)。
 */
public final class EolAgnosticText {

    private EolAgnosticText() {
    }

    /** 归一化到 LF:兼容 CRLF / CR / LF 三种行尾。 */
    public static String toLf(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 把已归一化为 LF 的文本转成目标行尾(eol 为 "\r\n" 或 "\n")。 */
    public static String fromLf(String lfText, String eol) {
        return eol.equals("\n") ? lfText : lfText.replace("\n", "\r\n");
    }

    /** 是否为换行字符(\r 或 \n)。 */
    public static boolean isEol(char c) {
        return c == '\r' || c == '\n';
    }

    /** 跳过 i 处的一个换行单元:\r\n 跳 2,孤立 \r / \n 跳 1;返回新的位置。 */
    public static int skipEol(String s, int i) {
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
     * <p>单遍扫描,天然无环(历史上的死循环缺陷见 findUniqueEolAgnostic 的注释)。
     */
    public static int matchAt(String haystack, String needle, int pos) {
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
    public static int[] findUniqueEolAgnostic(String haystack, String needle) {
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
}
