package dev.everyagent.worker.modules;

import dev.everyagent.plugin.api.exception.NotFoundException;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * ConfigStore 单测(无 Spring):configId 唯一性校验 + 设置页增删的落盘/热重建。
 */
class ConfigStoreTest {

    private static final HubPool POOL = mock(HubPool.class);

    @TempDir
    Path homeDir;

    @Test
    void duplicateConfigIdFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new ConfigStore(props(
                        model("a", "pa", "ma"),
                        model("a", "pb", "mb")), POOL));
    }

    @Test
    void addModelPersistsToExternalYaml() throws Exception {
        WorkerProperties props = props(model("a", "pa", "ma"));
        props.setHomeDir(homeDir.toString());
        ConfigStore store = new ConfigStore(props, POOL);
        // 外部文件预置其他配置键,验证写入时被保留。
        Path external = homeDir.resolve("application-worker.yaml");
        Files.writeString(external, "worker:\n  worker-id: test-worker\n");
        WorkerProperties.Model added = model("b", "pb", "mb");
        added.setFullUrl("https://api.example.com/chat/completions");
        assertEquals(2, store.addModel(added));
        assertEquals(2, store.list().size());
        // 重复 configId 拒绝。
        assertThrows(IllegalArgumentException.class, () -> store.addModel(model("b", "px", "mx")));
        // 缺模型名/base-url+full-url 拒绝。
        WorkerProperties.Model invalid = model("c", "pc", "");
        assertThrows(IllegalArgumentException.class, () -> store.addModel(invalid));
        WorkerProperties.Model noUrl = model("c", "pc", "mc");
        noUrl.setBaseUrl(null);
        assertThrows(IllegalArgumentException.class, () -> store.addModel(noUrl));
        // 文件断言:models 段含 a/b 两条,其他键保留。
        try (InputStream in = Files.newInputStream(external)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<?, ?> worker = (Map<?, ?>) root.get("worker");
            assertEquals("test-worker", worker.get("worker-id"));
            List<?> models = (List<?>) worker.get("models");
            assertEquals(2, models.size());
            Map<?, ?> b = (Map<?, ?>) models.get(1);
            assertEquals("b", b.get("config-id"));
            assertEquals("pb", b.get("provider"));
            assertEquals("https://api.example.com/chat/completions", b.get("full-url"));
            assertEquals("sk-b", b.get("api-key"));
        }
        // 热重建生效:resolve 可命中新条目。
        assertEquals("b", store.resolve("b").snapshot().configId());
    }

    @Test
    void removeModelPersistsToExternalYaml() throws Exception {
        WorkerProperties props = props(model("a", "pa", "ma"), model("b", "pb", "mb"));
        props.setHomeDir(homeDir.toString());
        ConfigStore store = new ConfigStore(props, POOL);
        Path external = homeDir.resolve("application-worker.yaml");
        assertEquals(1, store.removeModel("a"));
        assertEquals(1, store.list().size());
        assertNull(store.resolveOrNull("a"));
        assertNotNull(store.resolveOrNull("b"));
        // 不存在 → NOT_FOUND。
        assertThrows(NotFoundException.class, () -> store.removeModel("a"));
        // 文件断言:只剩 b。
        try (InputStream in = Files.newInputStream(external)) {
            Map<String, Object> root = new Yaml().load(in);
            List<?> models = (List<?>) ((Map<?, ?>) root.get("worker")).get("models");
            assertEquals(1, models.size());
            assertEquals("b", ((Map<?, ?>) models.get(0)).get("config-id"));
        }
        // 删除后外部文件 worker.models 仍可读(reload 口径)。
        assertEquals(1, store.reload());
        assertTrue(store.list().stream().anyMatch(c -> c.configId().equals("b")));
    }

    // ---- 辅助 ----

    private static WorkerProperties props(WorkerProperties.Model... models) {
        WorkerProperties props = new WorkerProperties();
        props.setModels(new ArrayList<>(List.of(models)));
        return props;
    }

    private static WorkerProperties.Model model(String configId, String provider, String model) {
        WorkerProperties.Model m = new WorkerProperties.Model();
        m.setConfigId(configId);
        m.setProvider(provider);
        m.setBaseUrl("http://" + configId);
        m.setModel(model);
        m.setApiKey("sk-" + configId);
        return m;
    }
}
