package dev.everyagent.worker.modules;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;

/**
 * {@link SkillExclusionStore} 的持久化行为测试:回退策略 / round-trip / 原子性 / 并发写。
 *
 * <p>与 {@link UserPreferenceStore} 同模式的落盘读写,但形态是
 * {@code {"<skillId>":["<workspaceRoot>",...]}};核心不变量:
 * <b>任何读盘失败都回退空表(= 全部暴露)、绝不抛出影响启动的异常</b>;
 * 写后内存态与磁盘态一致(synchronized + 原子替换)。
 */
class SkillExclusionStoreTest {

    @TempDir
    Path tmp;

    private Path homeDir() throws Exception {
        Path home = Files.createDirectory(tmp.resolve("home"));
        return home;
    }

    private SkillExclusionStore newStore(Path home) {
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(home.toString());
        SkillExclusionStore store = new SkillExclusionStore(props);
        store.init(); // @PostConstruct 同款入口(包内可见)
        return store;
    }

    private Path file(Path home) {
        return home.resolve("skill-exclusions.json");
    }

    @Test
    void missingFileFallsBackToEmpty() throws Exception {
        Path home = homeDir();
        SkillExclusionStore store = newStore(home);
        assertTrue(store.snapshot().isEmpty(), "缺文件回退空表(= 全部暴露)");
        assertTrue(store.excludedWorkspaces("plan").isEmpty());
    }

    @Test
    void blankFileFallsBackToEmpty() throws Exception {
        Path home = homeDir();
        Files.writeString(file(home), "   \n  \n");
        SkillExclusionStore store = assertDoesNotThrow(() -> newStore(home),
                "空(空白)JSON 文件不得抛出,回退空表");
        assertTrue(store.snapshot().isEmpty(), "空白文件回退空表");
    }

    @Test
    void corruptJsonFallsBackToEmptyWithoutThrowing() throws Exception {
        Path home = homeDir();
        Files.writeString(file(home), "{\"plan\": [\"C:\\\\ws\""); // 未闭合 + 转义残缺,解析必败
        SkillExclusionStore store = assertDoesNotThrow(() -> newStore(home),
                "损坏 JSON 只能 WARN 回退,不得抛出影响启动的异常");
        assertTrue(store.snapshot().isEmpty(), "损坏 JSON 回退空表(= 全部暴露)");
    }

    @Test
    void roundTripPersistsAcrossReload() throws Exception {
        Path home = homeDir();
        SkillExclusionStore store = newStore(home);
        store.setExcludedWorkspaces("plan", List.of("C:\\ws\\alpha", "C:\\ws\\beta"));
        store.setExcludedWorkspaces("md-to-pdf", List.of("C:\\ws\\beta"));

        // 新实例(模拟 worker 重启)从磁盘读回应与写入态一致
        SkillExclusionStore reloaded = newStore(home);
        assertEquals(
                Map.of(
                        "plan", Set.of("C:\\ws\\alpha", "C:\\ws\\beta"),
                        "md-to-pdf", Set.of("C:\\ws\\beta")),
                reloaded.snapshot(),
                "排除配置经落盘→重读 round-trip 不变形");
        assertEquals(Set.of("C:\\ws\\alpha", "C:\\ws\\beta"), reloaded.excludedWorkspaces("plan"));
        assertTrue(reloaded.excludedWorkspaces("agent-dispatch").isEmpty(), "未配置的 skill 默认全部暴露");
    }

    @Test
    void writeThenImmediateReadBack() throws Exception {
        Path home = homeDir();
        SkillExclusionStore store = newStore(home);
        store.setExcludedWorkspaces("plan", List.of("C:\\ws\\alpha"));
        // 写后立即读回:内存读 & 磁盘读都应得到完整内容(原子替换,无半截文件窗口)
        assertEquals(Set.of("C:\\ws\\alpha"), store.excludedWorkspaces("plan"), "内存即时读回");
        assertTrue(Files.isRegularFile(file(home)), "落盘文件已存在");
        String disk = Files.readString(file(home));
        assertEquals(Set.of("C:\\ws\\alpha"),
                Set.of(Json.parse(disk).path("plan").path(0).asString()),
                "磁盘即时读回为完整合法 JSON");
    }

    @Test
    void emptyCollectionClearsKeyAndPersists() throws Exception {
        Path home = homeDir();
        SkillExclusionStore store = newStore(home);
        store.setExcludedWorkspaces("plan", List.of("C:\\ws\\alpha"));
        assertFalse(store.snapshot().isEmpty());

        store.setExcludedWorkspaces("plan", List.of()); // 空集合 = 清空并删除键

        assertTrue(store.excludedWorkspaces("plan").isEmpty(), "空集合恢复全部暴露");
        assertFalse(store.snapshot().containsKey("plan"), "键已删除(不保留空清单噪声条目)");
        assertFalse(Json.parse(Files.readString(file(home))).has("plan"), "删除键已落盘");
    }

    @Test
    void concurrentWritesKeepMemoryAndDiskConsistent() throws Exception {
        Path home = homeDir();
        SkillExclusionStore store = newStore(home);
        int threads = 8;
        int iterations = 60;
        List<String> skillIds = List.of("plan", "skill-creator", "agent-dispatch", "md-to-pdf");
        List<String> roots = List.of("C:\\ws\\alpha", "C:\\ws\\beta", "C:\\ws\\gamma");
        CountDownLatch start = new CountDownLatch(1);
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            workers[t] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < iterations; i++) {
                    String skill = skillIds.get((seed + i) % skillIds.size());
                    String root = roots.get((seed * 7 + i * 3) % roots.size());
                    // 交错覆盖式写:同 skill 多线程互踩 + 偶发清空
                    if ((seed + i) % 11 == 0) {
                        store.setExcludedWorkspaces(skill, List.of());
                    } else {
                        store.setExcludedWorkspaces(skill, List.of(root));
                    }
                }
            });
            workers[t].start();
        }
        start.countDown();
        for (Thread w : workers) {
            w.join(30_000);
        }

        // synchronized + 原子替换的不变量:最终内存态 == 最终磁盘态(重读一致、文件合法未损坏)
        Map<String, Set<String>> fromDisk = assertDoesNotThrow(() -> newStore(home).snapshot(),
                "并发写后磁盘文件必须仍是合法 JSON(未损坏)");
        assertEquals(store.snapshot(), fromDisk,
                "并发写结束后内存态与磁盘重读态一致(每个 skill 的清单要么 0 项要么恰 1 项)");
        for (Map.Entry<String, Set<String>> e : fromDisk.entrySet()) {
            assertTrue(e.getValue().isEmpty() || e.getValue().size() == 1,
                    "交错写只可能落成 空清单(已删键,不会出现)/单根 清单: " + e);
            assertTrue(roots.containsAll(e.getValue()), "值只能来自写入集合: " + e);
        }
    }
}
