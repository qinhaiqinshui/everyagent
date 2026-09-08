package dev.everyagent.worker.modules;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * ConfigStore 池配置单测(无 Spring):{@code provider: model-pool} 条目的成员解析与校验。
 */
class ConfigStoreTest {

    private static final HubPool POOL = mock(HubPool.class);

    @Test
    void poolConfigResolvesMembers() {
        ConfigStore store = new ConfigStore(props(
                model("deepseek", "deepseek", "deepseek-chat"),
                model("qwen", "qwen", "qwen-max"),
                model("main-with-failover", "model-pool", "deepseek,qwen")), POOL);

        ResolvedConfig cfg = store.resolve("main-with-failover");
        assertTrue(cfg.isPool(), "池配置 isPool 应为 true");
        assertEquals(2, cfg.poolMembers().size(), "池成员应为 2 个");
        assertEquals("deepseek", cfg.poolMembers().get(0).snapshot().configId(), "首个成员 = 主模型 deepseek");
        assertFalse(cfg.poolMembers().get(0).isPool(), "成员本身不是池");
        assertEquals("deepseek-chat", cfg.poolMembers().get(0).snapshot().model());
        assertEquals("qwen-max", cfg.poolMembers().get(1).snapshot().model());
        // config.get 展示:池条目 members 填列表,普通条目 members 为 null
        var list = store.list();
        var poolDto = list.stream().filter(c -> "main-with-failover".equals(c.configId())).findFirst().orElseThrow();
        assertEquals(List.of("deepseek", "qwen"), poolDto.members());
        var plainDto = list.stream().filter(c -> "deepseek".equals(c.configId())).findFirst().orElseThrow();
        assertNull(plainDto.members(), "普通模型 members 应为 null");
    }

    @Test
    void poolMembersDedupKeepsOrder() {
        ConfigStore store = new ConfigStore(props(
                model("a", "pa", "ma"),
                model("b", "pb", "mb"),
                model("pool", "model-pool", "a,a,b,a")), POOL);
        ResolvedConfig cfg = store.resolve("pool");
        assertEquals(List.of("a", "b"),
                cfg.poolMembers().stream().map(m -> m.snapshot().configId()).toList(),
                "重复成员去重且保持顺序(首个即主模型)");
    }

    @Test
    void normalModelMissingModelFailsFast() {
        WorkerProperties.Model m = model("broken", "openai-compat", null);
        assertThrows(IllegalStateException.class, () -> new ConfigStore(props(m), POOL));
    }

    @Test
    void poolReferencingMissingMemberFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new ConfigStore(props(
                        model("a", "pa", "ma"),
                        model("pool", "model-pool", "a,no-such")), POOL));
    }

    @Test
    void poolNestedPoolFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new ConfigStore(props(
                        model("a", "pa", "ma"),
                        model("inner", "model-pool", "a"),
                        model("pool", "model-pool", "inner,a")), POOL));
    }

    @Test
    void poolMissingModelFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new ConfigStore(props(
                        model("a", "pa", "ma"),
                        model("pool", "model-pool", null)), POOL));
    }

    @Test
    void duplicateConfigIdFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new ConfigStore(props(
                        model("a", "pa", "ma"),
                        model("a", "pb", "mb")), POOL));
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