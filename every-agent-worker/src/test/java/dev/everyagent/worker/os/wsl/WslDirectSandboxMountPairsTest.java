package dev.everyagent.worker.os.wsl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link WslDirectSandbox#mountPairs} 契约:宿主上已不存在的目录(任务数据目录被清理、
 * 外部授权根失效等)不进挂载载荷——否则 runner 每条命令都白挂一次 drvfs 失败并打
 * 「[sandbox] 挂载失败」stderr 噪音;目录复活后下次命令靠幂等 _ensure_mount 自愈重挂。
 * 另钉住系统技能目录只读挂载对(ro:true)契约(§7.17)。
 */
class WslDirectSandboxMountPairsTest {

    @TempDir
    Path tmp;

    @Test
    void skipsNonexistentWorkspaceRoots() {
        Path gone = tmp.resolve("deleted-task-dir"); // 存在过的任务数据目录已被清理
        List<Map<String, String>> pairs = WslDirectSandbox.mountPairs(List.of(gone), null, null);
        assertTrue(pairs.isEmpty(), "不存在的目录不得进挂载载荷: " + pairs);
    }

    @Test
    void skipsNonexistentCwd() {
        Path gone = tmp.resolve("gone-cwd");
        assertTrue(WslDirectSandbox.mountPairs(null, gone, null).isEmpty());
        assertTrue(WslDirectSandbox.mountPairs(List.of(gone), gone, null).isEmpty(),
                "cwd 与列表去重后同样跳过");
    }

    @Test
    void keepsExistingDirectories() {
        // 正例只在 Windows 上有意义:toDirectMount 要求盘符路径;存在性过滤不得误杀真实工作区
        assumeTrue(System.getProperty("os.name").toLowerCase().startsWith("win"));
        List<Map<String, String>> pairs = WslDirectSandbox.mountPairs(List.of(tmp), tmp, null);
        assertEquals(1, pairs.size());
        assertEquals(tmp.toString(), pairs.get(0).get("src"));
        assertEquals(WslPathMapper.toDirectMount(tmp), pairs.get(0).get("dest"));
    }

    @Test
    void keepsOnlyExistingOnesFromMixedList() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().startsWith("win"));
        Path alive = Files.createDirectory(tmp.resolve("alive"));
        Path gone = tmp.resolve("gone");
        List<Map<String, String>> pairs = WslDirectSandbox.mountPairs(List.of(gone, alive), null, null);
        assertEquals(1, pairs.size(), "只剩真实存在的目录: " + pairs);
        assertEquals(alive.toString(), pairs.get(0).get("src"));
    }

    @Test
    void mountsSkillsDirReadOnly() throws Exception {
        // 系统技能目录以只读挂载对加入载荷(ro:true,§7.17);正例只在 Windows 上有意义
        assumeTrue(System.getProperty("os.name").toLowerCase().startsWith("win"));
        Path skills = Files.createDirectory(tmp.resolve("skills"));
        List<Map<String, String>> pairs = WslDirectSandbox.mountPairs(null, tmp, skills);
        // cwd + skills 两对;skills 对带 ro:true,cwd 对无 ro
        assertEquals(2, pairs.size(), pairs.toString());
        Map<String, String> skillsPair = pairs.stream()
                .filter(p -> "true".equals(p.get("ro"))).findFirst().orElseThrow();
        assertEquals(skills.toString(), skillsPair.get("src"));
        assertEquals(WslPathMapper.toDirectMount(skills), skillsPair.get("dest"));
        // 工作区对不得带 ro(读写挂载)
        assertTrue(pairs.stream().noneMatch(p -> tmp.toString().equals(p.get("src"))
                && p.containsKey("ro")));
    }

    @Test
    void skipsSkillsDirOverlappingWorkspace() {
        // 技能目录与工作区根 dest 重叠时(配置异常,默认布局不发生)跳过只读对,避免重复挂载
        assumeTrue(System.getProperty("os.name").toLowerCase().startsWith("win"));
        List<Map<String, String>> pairs = WslDirectSandbox.mountPairs(null, tmp, tmp);
        assertEquals(1, pairs.size(), "重叠时只挂工作区读写对: " + pairs);
        assertNull(pairs.get(0).get("ro"));
    }

    @Test
    void skipsNonexistentSkillsDir() {
        Path gone = tmp.resolve("no-skills");
        List<Map<String, String>> pairs = WslDirectSandbox.mountPairs(null, gone, gone);
        assertTrue(pairs.isEmpty(), "不存在的技能目录不进载荷: " + pairs);
    }
}
