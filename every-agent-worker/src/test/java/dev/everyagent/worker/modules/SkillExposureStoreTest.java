package dev.everyagent.worker.modules;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import dev.everyagent.worker.modules.SkillExposureStore.ExposureConfig;

/**
 * {@link SkillExposureStore} 的持久化与双清单(黑名单优先)语义测试:
 * 回退策略 / round-trip / 原子性 / 旧格式迁移 / 并发写 / 黑白名单并存。
 *
 * <p>核心不变量:
 * <ul>
 *   <li>任何读盘失败(缺文件/空文件/损坏 JSON)都回退空表(= 全部暴露),绝不抛出影响启动的异常;</li>
 *   <li>写后内存态与磁盘态一致(synchronized + 原子替换);</li>
 *   <li>黑名单优先:同时在黑白名单中的工作区按黑名单处理(不暴露);</li>
 *   <li>两个清单都空 = 删除键(默认全部暴露);</li>
 *   <li>旧版 skill-exclusions.json(数组值)自动迁移为 blacklist;</li>
 *   <li>旧版单模式 {"mode":"blacklist"/"whitelist","workspaces":[...]} 自动迁移为对应清单。</li>
 * </ul>
 */
class SkillExposureStoreTest {

    @TempDir
    Path tmp;

    private Path homeDir() throws Exception {
        return Files.createDirectory(tmp.resolve("home"));
    }

    private SkillExposureStore newStore(Path home) {
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(home.toString());
        SkillExposureStore store = new SkillExposureStore(props);
        store.init(); // @PostConstruct 同款入口(包内可见)
        return store;
    }

    private Path file(Path home) {
        return home.resolve("skill-exposure.json");
    }

    private Path oldFile(Path home) {
        return home.resolve("skill-exclusions.json");
    }

    // ── 回退策略 ──────────────────────────────────────────────

    @Test
    void missingFileFallsBackToAllExposed() throws Exception {
        Path home = homeDir();
        SkillExposureStore store = newStore(home);
        assertTrue(store.snapshot().isEmpty(), "缺文件回退空表(= 全部暴露)");
        assertTrue(store.isExposed("plan", "C:\\ws"), "未配置 skill 全部暴露");
        assertNull(store.getConfig("plan"), "未配置 skill getConfig 返回 null");
    }

    @Test
    void blankFileFallsBackToAllExposed() throws Exception {
        Path home = homeDir();
        Files.writeString(file(home), "   \n  \n");
        SkillExposureStore store = assertDoesNotThrow(() -> newStore(home),
                "空(空白)JSON 文件不得抛出,回退空表");
        assertTrue(store.snapshot().isEmpty(), "空白文件回退空表");
        assertTrue(store.isExposed("plan", "C:\\ws"), "空白文件 = 全部暴露");
    }

    @Test
    void corruptJsonFallsBackToAllExposedWithoutThrowing() throws Exception {
        Path home = homeDir();
        Files.writeString(file(home), "{\"plan\": {\"blacklist\":[\"C:\\\\ws\"]"); // 未闭合,解析必败
        SkillExposureStore store = assertDoesNotThrow(() -> newStore(home),
                "损坏 JSON 只能 WARN 回退,不得抛出影响启动的异常");
        assertTrue(store.snapshot().isEmpty(), "损坏 JSON 回退空表(= 全部暴露)");
        assertTrue(store.isExposed("plan", "C:\\ws"), "损坏 JSON = 全部暴露");
    }

    // ── round-trip 持久化 ────────────────────────────────────

    @Test
    void roundTripPersistsAcrossReload() throws Exception {
        Path home = homeDir();
        SkillExposureStore store = newStore(home);
        store.setConfig("plan", List.of("C:\\ws\\alpha", "C:\\ws\\beta"), List.of());
        store.setConfig("md-to-pdf", List.of(), List.of("C:\\ws\\beta"));
        store.setConfig("agent-dispatch", List.of("C:\\ws\\alpha"), List.of("C:\\ws\\beta"));

        // 新实例(模拟 worker 重启)从磁盘读回应与写入态一致
        SkillExposureStore reloaded = newStore(home);
        Map<String, ExposureConfig> snap = reloaded.snapshot();
        assertEquals(3, snap.size(), "三条配置经 round-trip 保留");

        ExposureConfig plan = reloaded.getConfig("plan");
        assertNotNull(plan);
        assertEquals(Set.of("C:\\ws\\alpha", "C:\\ws\\beta"), plan.blacklist());
        assertTrue(plan.whitelist().isEmpty());

        ExposureConfig mdp = reloaded.getConfig("md-to-pdf");
        assertNotNull(mdp);
        assertTrue(mdp.blacklist().isEmpty());
        assertEquals(Set.of("C:\\ws\\beta"), mdp.whitelist());

        ExposureConfig ad = reloaded.getConfig("agent-dispatch");
        assertNotNull(ad);
        assertEquals(Set.of("C:\\ws\\alpha"), ad.blacklist());
        assertEquals(Set.of("C:\\ws\\beta"), ad.whitelist());

        assertNull(reloaded.getConfig("skill-creator"), "未配置的 skill 默认全部暴露");
        assertTrue(reloaded.isExposed("skill-creator", "C:\\ws\\any"), "未配置 = 全部暴露");
    }

    // ── 写后即时读回 ─────────────────────────────────────────

    @Test
    void writeThenImmediateReadBack() throws Exception {
        Path home = homeDir();
        SkillExposureStore store = newStore(home);
        store.setConfig("plan", List.of("C:\\ws\\alpha"), List.of("C:\\ws\\beta"));
        // 写后立即读回:内存读 & 磁盘读都应得到完整内容(原子替换,无半截文件窗口)
        ExposureConfig cfg = store.getConfig("plan");
        assertNotNull(cfg);
        assertEquals(Set.of("C:\\ws\\alpha"), cfg.blacklist(), "内存即时读回 blacklist");
        assertEquals(Set.of("C:\\ws\\beta"), cfg.whitelist(), "内存即时读回 whitelist");
        assertTrue(Files.isRegularFile(file(home)), "落盘文件已存在");
        String disk = Files.readString(file(home));
        assertEquals("C:\\ws\\alpha",
                Json.parse(disk).path("plan").path("blacklist").path(0).asString(),
                "磁盘即时读回 blacklist 正确");
        assertEquals("C:\\ws\\beta",
                Json.parse(disk).path("plan").path("whitelist").path(0).asString(),
                "磁盘即时读回 whitelist 正确");
    }

    // ── 两个清单都空 = 删除键 ──────────────────────────────────

    @Test
    void bothEmptyDeletesKey() throws Exception {
        Path home = homeDir();
        SkillExposureStore store = newStore(home);
        store.setConfig("plan", List.of("C:\\ws\\alpha"), List.of());
        assertFalse(store.snapshot().isEmpty());

        store.setConfig("plan", List.of(), List.of()); // 都空 = 删除键(恢复默认)

        assertTrue(store.isExposed("plan", "C:\\ws\\alpha"), "都空 = 全部暴露");
        assertNull(store.getConfig("plan"), "键已删除(不保留噪声条目)");
        assertFalse(store.snapshot().containsKey("plan"), "快照中无 plan 键");
        assertFalse(Json.parse(Files.readString(file(home))).has("plan"), "删除键已落盘");
    }

    // ── 黑名单语义 ───────────────────────────────────────────

    @Test
    void blacklistExposesAllExceptListed() throws Exception {
        Path home = homeDir();
        SkillExposureStore store = newStore(home);
        store.setConfig("plan", List.of("C:\\ws\\alpha"), List.of());

        assertFalse(store.isExposed("plan", "C:\\ws\\alpha"), "黑名单:列出的工作区不暴露");
        assertTrue(store.isExposed("plan", "C:\\ws\\beta"), "黑名单:未列出的工作区暴露");
        assertTrue(store.isExposed("plan", "C:\\ws\\gamma"), "黑名单:其余全部暴露");
    }

    // ── 白名单语义 ───────────────────────────────────────────

    @Test
    void whitelistOnlyExposesListedWorkspaces() {
        Path home = null;
        try {
            home = homeDir();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        SkillExposureStore store = newStore(home);
        store.setConfig("plan", List.of(), List.of("C:\\ws\\alpha", "C:\\ws\\beta"));

        assertTrue(store.isExposed("plan", "C:\\ws\\alpha"), "白名单:列出的工作区暴露");
        assertTrue(store.isExposed("plan", "C:\\ws\\beta"), "白名单:列出的工作区暴露");
        assertFalse(store.isExposed("plan", "C:\\ws\\gamma"), "白名单:未列出的工作区不暴露");
    }

    @Test
    void whitelistWithEmptyWorkspacesDoesNotRestrict() throws Exception {
        Path home = homeDir();
        SkillExposureStore store = newStore(home);
        // 白名单空 = 不限制(与都空一样,删除键)
        store.setConfig("plan", List.of(), List.of());
        assertNull(store.getConfig("plan"), "白名单空 + 黑名单空 = 删键");
        assertTrue(store.isExposed("plan", "C:\\ws\\alpha"), "白名单空 = 不限制 = 全部暴露");
    }

    // ── 黑白名单并存(黑名单优先)────────────────────────────

    @Test
    void blacklistOverridesWhitelistWhenBothContainRoot() {
        Path home = null;
        try {
            home = homeDir();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        SkillExposureStore store = newStore(home);
        // ws 在黑白名单中都存在 → 黑名单胜出(不暴露)
        store.setConfig("plan", List.of("C:\\ws\\alpha"), List.of("C:\\ws\\alpha", "C:\\ws\\beta"));

        assertFalse(store.isExposed("plan", "C:\\ws\\alpha"),
                "同时命中黑白名单:黑名单优先(不暴露)");
        assertTrue(store.isExposed("plan", "C:\\ws\\beta"),
                "仅在白名单中:白名单放行(暴露)");
        assertFalse(store.isExposed("plan", "C:\\ws\\gamma"),
                "白名单非空且不在列表中:不暴露");
    }

    @Test
    void blacklistAndWhitelistCoexistWithDistinctRoots() {
        Path home = null;
        try {
            home = homeDir();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        SkillExposureStore store = newStore(home);
        // 黑名单=[alpha], 白名单=[beta, gamma]
        store.setConfig("plan", List.of("C:\\ws\\alpha"), List.of("C:\\ws\\beta", "C:\\ws\\gamma"));

        assertFalse(store.isExposed("plan", "C:\\ws\\alpha"), "黑名单命中:不暴露");
        assertTrue(store.isExposed("plan", "C:\\ws\\beta"), "白名单命中:暴露");
        assertTrue(store.isExposed("plan", "C:\\ws\\gamma"), "白名单命中:暴露");
        assertFalse(store.isExposed("plan", "C:\\ws\\delta"), "不在白名单中:不暴露");
    }

    // ── 旧格式迁移:最旧版数组值 ───────────────────────────────

    @Test
    void oldArrayFormatMigratesToBlacklist() throws Exception {
        Path home = homeDir();
        // 最旧版 skill-exclusions.json: {"plan": ["C:\\ws\\alpha"]}
        Files.writeString(oldFile(home), "{\"plan\": [\"C:\\\\ws\\\\alpha\"]}");

        SkillExposureStore store = newStore(home);

        // 迁移后应等价于 blacklist + ["C:\\ws\\alpha"]
        ExposureConfig cfg = store.getConfig("plan");
        assertNotNull(cfg, "旧格式条目已迁移");
        assertEquals(Set.of("C:\\ws\\alpha"), cfg.blacklist(), "迁移为 blacklist 清单");
        assertTrue(cfg.whitelist().isEmpty(), "whitelist 为空");
        assertFalse(store.isExposed("plan", "C:\\ws\\alpha"), "迁移语义:列出的不暴露");
        assertTrue(store.isExposed("plan", "C:\\ws\\beta"), "迁移语义:其余暴露");

        // 迁移应立即落盘为新格式 skill-exposure.json
        assertTrue(Files.isRegularFile(file(home)), "迁移后新格式文件已落盘");
        String disk = Files.readString(file(home));
        assertEquals("C:\\ws\\alpha",
                Json.parse(disk).path("plan").path("blacklist").path(0).asString(),
                "落盘为新格式(blacklist 数组)");
        // 再次加载(新实例)应从新格式读取
        SkillExposureStore reloaded = newStore(home);
        ExposureConfig cfg2 = reloaded.getConfig("plan");
        assertNotNull(cfg2);
        assertEquals(Set.of("C:\\ws\\alpha"), cfg2.blacklist());
        assertTrue(cfg2.whitelist().isEmpty());
    }

    @Test
    void oldArrayFormatEmptyEntryDoesNotMigrate() throws Exception {
        Path home = homeDir();
        Files.writeString(oldFile(home), "{\"plan\": []}");
        SkillExposureStore store = newStore(home);
        assertNull(store.getConfig("plan"), "空数组旧条目不迁移为配置(= 默认全部暴露)");
        assertTrue(store.isExposed("plan", "C:\\ws"), "空旧条目 = 全部暴露");
    }

    // ── 旧格式迁移:单模式 mode+workspaces ───────────────────

    @Test
    void oldModeBlacklistMigratesToBlacklist() throws Exception {
        Path home = homeDir();
        Files.writeString(file(home),
                "{\"plan\": {\"mode\": \"blacklist\", \"workspaces\": [\"C:\\\\ws\\\\alpha\"]}}");
        SkillExposureStore store = newStore(home);
        ExposureConfig cfg = store.getConfig("plan");
        assertNotNull(cfg, "旧 mode=blacklist 条目已迁移");
        assertEquals(Set.of("C:\\ws\\alpha"), cfg.blacklist(), "迁移到 blacklist 清单");
        assertTrue(cfg.whitelist().isEmpty(), "whitelist 为空");
    }

    @Test
    void oldModeWhitelistMigratesToWhitelist() throws Exception {
        Path home = homeDir();
        Files.writeString(file(home),
                "{\"plan\": {\"mode\": \"whitelist\", \"workspaces\": [\"C:\\\\ws\\\\alpha\", \"C:\\\\ws\\\\beta\"]}}");
        SkillExposureStore store = newStore(home);
        ExposureConfig cfg = store.getConfig("plan");
        assertNotNull(cfg, "旧 mode=whitelist 条目已迁移");
        assertTrue(cfg.blacklist().isEmpty(), "blacklist 为空");
        assertEquals(Set.of("C:\\ws\\alpha", "C:\\ws\\beta"), cfg.whitelist(), "迁移到 whitelist 清单");
    }

    // ── 并发写 ───────────────────────────────────────────────

    @Test
    void concurrentWritesKeepMemoryAndDiskConsistent() throws Exception {
        Path home = homeDir();
        SkillExposureStore store = newStore(home);
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
                    // 交错覆盖式写:同 skill 多线程互踩 + 偶发空清单(都空=删键)
                    if ((seed + i) % 11 == 0) {
                        store.setConfig(skill, List.of(), List.of());
                    } else if ((seed + i) % 7 == 0) {
                        store.setConfig(skill, List.of(), List.of(root)); // 白名单
                    } else {
                        store.setConfig(skill, List.of(root), List.of()); // 黑名单
                    }
                }
            });
            workers[t].start();
        }
        start.countDown();
        for (Thread w : workers) {
            w.join(30_000);
        }

        // synchronized + 原子替换的不变量:最终内存态 == 最终磁盘态
        Map<String, ExposureConfig> fromDisk = assertDoesNotThrow(() -> newStore(home).snapshot(),
                "并发写后磁盘文件必须仍是合法 JSON(未损坏)");
        assertEquals(store.snapshot(), fromDisk,
                "并发写结束后内存态与磁盘重读态一致");
        for (Map.Entry<String, ExposureConfig> e : fromDisk.entrySet()) {
            ExposureConfig cfg = e.getValue();
            // 交错写:每个清单要么空要么 1 项
            assertTrue(cfg.blacklist().isEmpty() || cfg.blacklist().size() == 1,
                    "交错写 blacklist 只可能 0/1 项: " + e);
            assertTrue(cfg.whitelist().isEmpty() || cfg.whitelist().size() == 1,
                    "交错写 whitelist 只可能 0/1 项: " + e);
            assertTrue(roots.containsAll(cfg.blacklist()), "blacklist 值只能来自写入集合: " + e);
            assertTrue(roots.containsAll(cfg.whitelist()), "whitelist 值只能来自写入集合: " + e);
        }
    }
}
