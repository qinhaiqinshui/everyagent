package dev.everyagent.worker.modules;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * ConfigStore 单测(无 Spring):configId 唯一性校验。
 */
class ConfigStoreTest {

    private static final HubPool POOL = mock(HubPool.class);

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
