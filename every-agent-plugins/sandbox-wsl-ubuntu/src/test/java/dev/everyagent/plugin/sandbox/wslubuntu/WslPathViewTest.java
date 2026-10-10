package dev.everyagent.plugin.sandbox.wslubuntu;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WslPathView} 纯映射契约测试（§7.8）。
 *
 * <p>锁死两条语义:①只有<b>已授权根及其子路径</b>才被翻译成发行版内形态,未授权路径
 * 原样返回宿主形态(不谎报);②回收后翻译立即失效、反向查询返回 null。
 */
class WslPathViewTest {

    private static final Path ROOT = Path.of("C:\\work\\proj");

    @Test
    void translatesGrantedRootAndChildrenOnly() {
        WslPathView view = new WslPathView();
        view.add(ROOT);

        assertEquals("/c/work/proj", view.toSandbox(ROOT), "授权根本身 → 挂载点");
        assertEquals("/c/work/proj/docs/a.md", view.toSandbox(ROOT.resolve("docs").resolve("a.md")),
                "子路径按挂载点前缀推导");

        Path other = Path.of("C:\\other\\x.txt");
        assertEquals(other.toString(), view.toSandbox(other), "未授权路径原样返回(不谎报)");
        assertFalse(view.contains(other));
        assertTrue(view.contains(ROOT.resolve("docs")));
    }

    @Test
    void reverseLookupReturnsHostPathOrNull() {
        WslPathView view = new WslPathView();
        view.add(ROOT);

        assertEquals(ROOT.normalize(), view.toHost("/c/work/proj"), "挂载点还原为授权根");
        assertEquals(ROOT.resolve("docs").resolve("a.md").normalize(),
                view.toHost("/c/work/proj/docs/a.md"), "子路径还原");
        assertNull(view.toHost("/c/elsewhere/x.txt"), "不在任何已授权挂载点内 → null");
        assertNull(view.toHost(null));
    }

    @Test
    void longestRootWinsForNestedGrants() {
        WslPathView view = new WslPathView();
        view.add(ROOT);
        Path nested = Path.of("D:\\data");
        view.add(nested);

        assertEquals("/d/data", view.toSandbox(nested), "多根各自映射");
        assertEquals("/c/work/proj", view.toSandbox(ROOT));
        assertEquals(nested.normalize(), view.toHost("/d/data"));
    }

    @Test
    void revokeStopsTranslationImmediately() {
        WslPathView view = new WslPathView();
        view.add(ROOT);
        assertEquals("/c/work/proj", view.toSandbox(ROOT));

        view.remove(ROOT);
        assertEquals(ROOT.toString(), view.toSandbox(ROOT), "回收后翻译立即失效");
        assertNull(view.toHost("/c/work/proj"), "回收后反向查询返回 null");
    }

    @Test
    void nonDrivePathIsNotMountedOrTranslated() {
        WslPathView view = new WslPathView();
        Path posix = Path.of("/home/dev/x");
        view.add(posix);
        assertTrue(view.contains(posix), "已记录为授权根");
        assertEquals(posix.toString(), view.toSandbox(posix),
                "非 Windows 盘路径拿不到挂载点 → 原样(不谎报)");
    }
}