package dev.everyagent.worker.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * read_file 无参截断逻辑的单元测试(通过反射访问 private static splitLines,
 * 避免依赖 fs 基础设施)。
 */
class FileToolsReadTruncateTest {

    /** 模拟 read_file 中截断分支的核心逻辑:计算截断行数。 */
    private static int[] computeCutLine(List<String> lines, int maxChars) {
        int acc = 0;
        int cutLine = 0;
        for (int i = 0; i < lines.size(); i++) {
            int lineLen = lines.get(i).length() + 1;
            if (acc + lineLen > maxChars && i > 0) {
                break;
            }
            acc += lineLen;
            cutLine = i + 1;
        }
        return new int[] {cutLine, acc};
    }

    private static List<String> splitLines(String s) throws Exception {
        Method m = FileTools.class.getDeclaredMethod("splitLines", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) m.invoke(null, s);
        return result;
    }

    @Test
    void shortFileNotTruncated() throws Exception {
        // 文件不超过阈值时不截断
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("line ").append(i).append("\n");
        }
        List<String> lines = splitLines(sb.toString());
        assertEquals(10, lines.size());
        int[] r = computeCutLine(lines, 30_000);
        assertEquals(10, r[0]); // 全部行都保留
    }

    @Test
    void longFileTruncated() throws Exception {
        // 构造一个超过阈值的文件:每行 10 字符 + "\n"
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append("0123456789\n");
        }
        List<String> lines = splitLines(sb.toString());
        int total = lines.size();
        int[] r = computeCutLine(lines, 30_000);
        int cutLine = r[0];
        // 应截断且截断行数 < 总行数
        assertTrue(cutLine > 0, "至少应有 1 行");
        assertTrue(cutLine < total, "应截断: cutLine=" + cutLine + " < total=" + total);
        // 截断后的行拼接的字符数应 <= 阈值
        assertTrue(r[1] <= 30_000, "字符数应在阈值内");
    }

    @Test
    void singleLongLineAlwaysReturned() throws Exception {
        // 单行超过阈值:仍返回该行(不能返回 0 行)
        String longLine = "x".repeat(50_000);
        List<String> lines = splitLines(longLine);
        assertEquals(1, lines.size());
        int[] r = computeCutLine(lines, 30_000);
        assertEquals(1, r[0], "单行长行仍应返回(至少 1 行)");
    }

    @Test
    void cutLineNeverZeroWhenLinesExist() throws Exception {
        // 多行但首行就超阈值:仍返回首行
        List<String> lines = List.of("x".repeat(50_000), "short");
        int[] r = computeCutLine(lines, 30_000);
        assertTrue(r[0] >= 1, "至少返回 1 行");
    }

    @Test
    void exactBoundaryNotTruncated() throws Exception {
        // 刚好在阈值内不截断: 2727 * 11 = 29997 <= 30000
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2727; i++) {
            sb.append("0123456789\n");
        }
        List<String> lines = splitLines(sb.toString());
        int[] r = computeCutLine(lines, 30_000);
        assertEquals(2727, r[0]);
    }
}
