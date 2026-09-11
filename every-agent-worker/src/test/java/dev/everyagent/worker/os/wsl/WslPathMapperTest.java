package dev.everyagent.worker.os.wsl;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link WslPathMapper} 的形态翻译契约:/workspace 与 /mnt/<盘> 双向、门禁命令副本翻译
 * (token 完整性:引号内含空格路径、边界不误替换、不可翻译路径原样保留)。
 */
class WslPathMapperTest {

    private static final Path WS = Path.of("C:\\Users\\haigui\\.yu\\s23ds84dsgl920dfbldf932gd\\eagent");

    @Test
    void toWslMapsDrivePaths() {
        assertEquals("/mnt/c", WslPathMapper.toWsl(Path.of("C:\\")));
        assertEquals("/mnt/c/Users/x", WslPathMapper.toWsl(Path.of("C:\\Users\\x")));
        assertEquals("/mnt/d/a b", WslPathMapper.toWsl(Path.of("d:\\a b")));
    }

    @Test
    void toWslRejectsUncAndRelative() {
        assertNull(WslPathMapper.toWsl(Path.of("\\\\server\\share"))); // UNC 无法直接判定形态
        assertNull(WslPathMapper.toWsl(Path.of("relative/path")));
        assertNull(WslPathMapper.toWsl(null));
    }

    @Test
    void toWindowsTokenMapsMountForms() {
        assertEquals("C:/Users/haigui/.yu/s23ds84dsgl920dfbldf932gd/eagent",
                WslPathMapper.toWindowsToken("/workspace", WS));
        assertEquals("C:/Users/haigui/.yu/s23ds84dsgl920dfbldf932gd/eagent/docs/a.md",
                WslPathMapper.toWindowsToken("/workspace/docs/a.md", WS));
        assertEquals("C:/Windows/System32", WslPathMapper.toWindowsToken("/mnt/c/Windows/System32", WS));
        assertEquals("D:/", WslPathMapper.toWindowsToken("/mnt/d", WS));
        assertEquals("D:/x", WslPathMapper.toWindowsToken("/mnt/D/x", WS)); // 盘符大小写不敏感
    }

    @Test
    void toWindowsTokenLeavesDistroInternalPathsNull() {
        // /etc 等发行版内部路径:只读基础层,门禁按工作区相对处理,不翻译
        assertNull(WslPathMapper.toWindowsToken("/etc/passwd", WS));
        assertNull(WslPathMapper.toWindowsToken("/usr/bin/env", WS));
        assertNull(WslPathMapper.toWindowsToken("src/main/java", WS));
    }

    @Test
    void translateCommandRewritesWorkspaceAndMnt() {
        assertEquals("rg -n foo \"C:/Users/haigui/.yu/s23ds84dsgl920dfbldf932gd/eagent/docs\"",
                WslPathMapper.translateCommand("rg -n foo \"/workspace/docs\"", WS));
        assertEquals("cat C:/Windows/win.ini",
                WslPathMapper.translateCommand("cat /mnt/c/Windows/win.ini", WS));
        assertEquals("ls D:/", WslPathMapper.translateCommand("ls /mnt/d", WS));
    }

    @Test
    void translateCommandKeepsUnknownAndBoundaryTokens() {
        // 发行版内部路径原样保留(无宿主映射,门禁按工作区相对处理)
        assertEquals("cat /etc/os-release",
                WslPathMapper.translateCommand("cat /etc/os-release", WS));
        // 边界:/workspace-x 不是挂载点,不得误替换
        assertEquals("ls /workspace-x", WslPathMapper.translateCommand("ls /workspace-x", WS));
        assertEquals("echo workspace", WslPathMapper.translateCommand("echo workspace", WS));
        // 相对路径不受影响
        assertEquals("rg -n foo src/",
                WslPathMapper.translateCommand("rg -n foo src/", WS));
    }

    @Test
    void translateCommandHandlesQuotedPathsWithSpaces() {
        // 引号内含空格:整段替换后仍是一个 QUOTED 段,token 完整性不受影响
        assertEquals("cat \"C:/Users/haigui/.yu/s23ds84dsgl920dfbldf932gd/eagent/a b.txt\"",
                WslPathMapper.translateCommand("cat \"/workspace/a b.txt\"", WS));
    }

    @Test
    void translateCommandDoesNotDoubleTranslate() {
        // 已是 Windows 形态的文本不含 /workspace、/mnt 前缀,幂等
        String win = "rg -n foo C:/Users/haigui/.yu/s23ds84dsgl920dfbldf932gd/eagent";
        assertEquals(win, WslPathMapper.translateCommand(win, WS));
    }

    // ---- toDirectMount:wsl-direct 原路径挂载点(工作区与外部授权根共用同一形态) ----

    @Test
    void toDirectMountMapsDrivePathsForAnyRoot() {
        // wsl-direct 的 mountPairs 对非工作区路径(外部授权根)按同一函数生成 {src,dest}
        assertEquals("/c/Users/ext/data", WslPathMapper.toDirectMount(Path.of("C:\\Users\\ext\\data")));
        assertEquals("/d/a b", WslPathMapper.toDirectMount(Path.of("d:\\a b")));
        assertEquals("/c", WslPathMapper.toDirectMount(Path.of("C:\\"))); // 盘根挂载点 /c
        assertEquals("/c/x", WslPathMapper.toDirectMount(Path.of("C:/x/"))); // 尾分隔符归一
    }

    @Test
    void toDirectMountRejectsUncAndRelative() {
        assertNull(WslPathMapper.toDirectMount(Path.of("\\\\server\\share")));
        assertNull(WslPathMapper.toDirectMount(Path.of("relative/path")));
        assertNull(WslPathMapper.toDirectMount(null));
    }
}
