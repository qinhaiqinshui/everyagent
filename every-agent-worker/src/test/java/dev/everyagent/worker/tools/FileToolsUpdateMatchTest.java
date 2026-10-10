package dev.everyagent.worker.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

/**
 * update_file 的 oldcontent 定位逻辑({@code findUniqueEolAgnostic})回归测试。
 *
 * <p>覆盖:主导行尾判定、行尾宽容匹配、唯一性校验、替换后 prefix/suffix 逐字节保留,
 * 以及**以换行开头的 oldcontent**(原实现先跳过换行位置再匹配,导致这类 oldcontent
 * 永远报「未找到旧内容」——本用例即该缺陷的回归护栏)。
 */
class FileToolsUpdateMatchTest {

    /** 反射调用 private static findUniqueEolAgnostic(String,String) → int[]{start,end,eolCode}。 */
    private static int[] find(String haystack, String needle) throws Exception {
        Method m = FileTools.class.getDeclaredMethod("findUniqueEolAgnostic", String.class, String.class);
        m.setAccessible(true);
        return (int[]) m.invoke(null, haystack, needle);
    }

    @Test
    void lfFileReturnsLfCode() throws Exception {
        int[] span = find("alpha\nbeta\ngamma\n", "beta");
        assertNotNull(span);
        assertEquals(0, span[2], "LF 文件主导行尾码应为 0");
    }

    @Test
    void crlfFileReturnsCrlfCode() throws Exception {
        int[] span = find("alpha\r\nbeta\r\ngamma\r\n", "beta");
        assertNotNull(span);
        assertEquals(1, span[2], "CRLF 文件主导行尾码应为 1");
    }

    @Test
    void oldcontentWithLfMatchesCrlfFile() throws Exception {
        // 模型按 read_file 的 LF 输出提供 oldcontent,而文件实际是 CRLF —— 必须仍能匹配
        int[] span = find("a\r\nb\r\nc", "a\nb");
        assertNotNull(span);
        assertEquals(0, span[0]);
        assertEquals(4, span[1], "应消费到 CRLF 之后 b 的末尾(index 4)");
    }

    @Test
    void leadingNewlineOldcontentMatches() throws Exception {
        // 回归:oldcontent 以换行开头(原实现跳过换行 → 永远找不到)
        int[] span = find("alpha\nbeta\ngamma", "\nbeta");
        assertNotNull(span, "以换行开头的 oldcontent 应能匹配");
        assertEquals(5, span[0]);
        assertEquals(10, span[1]);
    }

    @Test
    void notFoundReturnsNull() throws Exception {
        assertNull(find("alpha\nbeta\n", "zeta"));
    }

    @Test
    void duplicateThrows() throws Exception {
        assertIae("x\nDUP\ny\nDUP\n", "DUP");
    }

    @Test
    void overlappingDuplicateThrows() throws Exception {
        // "aaaa" 中 "aa" 有两处(重叠)→ 不唯一,必须报错而非任选一处
        assertIae("aaaa", "aa");
    }

    /** 反射调用会把异常包进 InvocationTargetException,这里解开再断言类型。 */
    private static void assertIae(String haystack, String needle) {
        InvocationTargetException ex =
                assertThrows(InvocationTargetException.class, () -> find(haystack, needle));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void spanPreservesPrefixAndSuffixByteForByte() throws Exception {
        String haystack = "头部\nOLD\n尾部\n";
        int[] span = find(haystack, "OLD");
        assertNotNull(span);
        String replaced = haystack.substring(0, span[0]) + "NEW" + haystack.substring(span[1]);
        assertEquals("头部\nNEW\n尾部\n", replaced, "替换区间之外必须逐字节保留");
    }
}