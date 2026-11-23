package dev.everyagent.plugin.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.EventLine;
import dev.everyagent.plugin.workflow.model.InstanceMeta;
import dev.everyagent.plugin.workflow.store.WorkflowStore;

/**
 * events.jsonl 单 writer 串行追加：多线程并发追加行完整性（无交错/无半行）、
 * seq 全局唯一、行数守恒；内存尾部与磁盘一致。
 */
class EventsConcurrencyTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 50;

    @TempDir
    Path home;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void concurrentAppendsKeepEveryLineIntact() throws Exception {
        FakeWorkspaces workspaces = new FakeWorkspaces(home);
        workspaces.register(home.resolve("proj"), "ws_evt");
        WorkflowStore store = new WorkflowStore(workspaces);
        Definition def = store.saveDefinition("ws_evt", DefinitionValidatorTest.withVariables(
                DefinitionValidatorTest.def(DefinitionValidatorTest.node("n1", "start")),
                new dev.everyagent.plugin.workflow.model.VariableDef("v", null, true, null, null)));
        InstanceMeta meta = store.createInstance(def, Map.of());

        var futures = new java.util.ArrayList<Future<Integer>>();
        for (int t = 0; t < THREADS; t++) {
            final int thread = t;
            futures.add(pool.submit((Callable<Integer>) () -> {
                int ok = 0;
                for (int i = 0; i < PER_THREAD; i++) {
                    EventLine line = store.appendEvent("ws_evt", meta.id(), "node.started",
                            Json.obj().put("nodeId", "n" + thread).put("i", i));
                    if (line.seq() != null && line.seqValue() > 0) {
                        ok++;
                    }
                }
                return ok;
            }));
        }
        int appended = 0;
        for (Future<Integer> f : futures) {
            appended += f.get(30, TimeUnit.SECONDS);
        }
        assertThat(appended).isEqualTo(THREADS * PER_THREAD);

        // 磁盘：行数守恒（+1 = wf.created）
        Path eventsFile = home.resolve("workspaces").resolve("ws_evt").resolve("workflows")
                .resolve("instances").resolve(meta.id()).resolve("events.jsonl");
        List<String> rawLines = Files.readAllLines(eventsFile);
        assertThat(rawLines).hasSize(THREADS * PER_THREAD + 1);
        // 每行都是合法 JSON（无交错/半行）
        for (String raw : rawLines) {
            assertThat(Json.parse(raw).path("event").asString()).isNotEmpty();
        }

        // 读取：全量、seq 唯一且递增
        List<EventLine> all = store.readEvents("ws_evt", meta.id(), 0, 0);
        assertThat(all).hasSize(THREADS * PER_THREAD + 1);
        List<Long> seqs = all.stream().map(EventLine::seqValue).collect(Collectors.toList());
        assertThat(seqs.stream().distinct().count()).isEqualTo((long) seqs.size());
        for (int i = 1; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isGreaterThan(seqs.get(i - 1));
        }

        // 内存尾部：有界（256），与磁盘末尾对齐
        List<EventLine> tail = store.tailEvents("ws_evt", meta.id(), 0);
        assertThat(tail).hasSize(256);
        assertThat(tail.get(tail.size() - 1).seq()).isEqualTo(all.get(all.size() - 1).seq());
        assertThat(tail.get(0).seq()).isEqualTo(all.get(all.size() - 256).seq());
    }
}
