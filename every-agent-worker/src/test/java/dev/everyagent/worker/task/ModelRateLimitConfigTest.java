package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelRateLimitConfig#from} 的「缺省回退全局默认 / 显式 0 关闭 / 显式值覆盖」语义单测。
 */
class ModelRateLimitConfigTest {

    private static WorkerProperties.ModelRate defaults() {
        WorkerProperties.ModelRate d = new WorkerProperties.ModelRate();
        d.setDefaultRpm(60);
        d.setDefaultMaxConcurrency(4);
        d.setDefaultTpm(0);
        return d;
    }

    @Test
    void missingFieldsFallBackToDefaults() {
        ModelRateLimitConfig cfg = ModelRateLimitConfig.from(Json.obj(), defaults());
        assertTrue(cfg.enabled(), "全局默认有 rpm/concurrency → 缺省应启用限流,不得裸奔");
        assertEquals(60, cfg.rpm(), "缺省 rpm 回退全局默认");
        assertEquals(4, cfg.maxConcurrency(), "缺省并发回退全局默认");
        assertEquals(0, cfg.tpm(), "缺省 tpm 回退全局默认(0)");
    }

    @Test
    void nullParamsFallBackToDefaults() {
        ModelRateLimitConfig cfg = ModelRateLimitConfig.from(null, defaults());
        assertTrue(cfg.enabled());
        assertEquals(60, cfg.rpm());
        assertEquals(4, cfg.maxConcurrency());
    }

    @Test
    void explicitZeroDisablesDimension() {
        JsonNode params = Json.obj()
                .put("rpm", 0)
                .put("maxConcurrency", 0);
        ModelRateLimitConfig cfg = ModelRateLimitConfig.from(params, defaults());
        assertFalse(cfg.enabled(), "显式 0 关闭 rpm/并发,tpm 默认亦 0 → 整体不限流");
        assertEquals(0, cfg.rpm());
        assertEquals(0, cfg.maxConcurrency());
    }

    @Test
    void explicitValueOverridesDefault() {
        JsonNode params = Json.obj()
                .put("rpm", 120)
                .put("maxConcurrency", 2)
                .put("tpm", 500_000);
        ModelRateLimitConfig cfg = ModelRateLimitConfig.from(params, defaults());
        assertEquals(120, cfg.rpm(), "显式 rpm 覆盖默认");
        assertEquals(2, cfg.maxConcurrency(), "显式并发覆盖默认");
        assertEquals(500_000, cfg.tpm(), "显式 tpm 覆盖默认");
    }

    @Test
    void partialOverrideKeepsDefaultForMissing() {
        JsonNode params = Json.obj().put("maxConcurrency", 2);
        ModelRateLimitConfig cfg = ModelRateLimitConfig.from(params, defaults());
        assertEquals(60, cfg.rpm(), "未写的 rpm 仍回退默认");
        assertEquals(2, cfg.maxConcurrency(), "显式并发覆盖");
        assertEquals(0, cfg.tpm(), "未写的 tpm 回退默认 0");
    }
}