package dev.everyagent.worker.task;

import tools.jackson.databind.JsonNode;

/**
 * 每模型限流配置(从 {@code worker.models[].params} 自由 JSON 解析,见
 * docs/design-model-rate-limit.md §4)。
 *
 * <p>支持字段(与现有 maxTokens/reasoningEffort 同处 params,可独立缺省):
 * <ul>
 *   <li>{@code rpm}            每分钟最多发起请求数(厂商套餐口径,滑动窗口);</li>
 *   <li>{@code maxConcurrency} 同一模型同时 in-flight 请求上限;长思考场景的核心闸门;</li>
 *   <li>{@code tpm}            厂商 tpm 上限(可选参考线,③④使用的目标);</li>
 *   <li>{@code tokenEstFactor} 估算系数初始值(持久化校准结果优先)。</li>
 * </ul>
 * 三项限流限制(rpm/maxConcurrency/tpm)全缺省/≤0 → {@link #enabled()} = false,
 * 即该模型不限流(现状兼容,升级无感)。
 */
public final class ModelRateLimitConfig {

    private final int rpm;
    private final int maxConcurrency;
    private final long tpm;
    private final double tokenEstFactor;

    public ModelRateLimitConfig(int rpm, int maxConcurrency, long tpm, double tokenEstFactor) {
        this.rpm = rpm;
        this.maxConcurrency = maxConcurrency;
        this.tpm = tpm;
        this.tokenEstFactor = tokenEstFactor;
    }

    public static ModelRateLimitConfig from(JsonNode params) {
        int rpm = 0;
        int concurrency = 0;
        long tpm = 0;
        double factor = 1.0;
        if (params != null && params.isObject()) {
            if (params.has("rpm")) {
                rpm = (int) params.path("rpm").asLong();
            }
            if (params.has("maxConcurrency")) {
                concurrency = (int) params.path("maxConcurrency").asLong();
            }
            if (params.has("tpm")) {
                tpm = params.path("tpm").asLong();
            }
            if (params.has("tokenEstFactor")) {
                double v = params.path("tokenEstFactor").asDouble();
                if (v > 0) {
                    factor = v;
                }
            }
        }
        return new ModelRateLimitConfig(Math.max(0, rpm), Math.max(0, concurrency),
                Math.max(0, tpm), Math.max(0.3, Math.min(3.0, factor)));
    }

    /** 是否启用限流(任一可数限制 > 0)。 */
    public boolean enabled() {
        return rpm > 0 || maxConcurrency > 0 || tpm > 0;
    }

    public int rpm() {
        return rpm;
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }

    public long tpm() {
        return tpm;
    }

    public double tokenEstFactor() {
        return tokenEstFactor;
    }
}