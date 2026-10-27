package dev.everyagent.worker.agent;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;

/**
 * 从模型返回的 usage 中提取缓存命中 token 数(适配器模式)。
 *
 * <p>不同厂商的 OpenAI 兼容 API 返回缓存命中 token 的字段各不相同:
 * <ul>
 *   <li>OpenAI / 智谱 / Kimi / 阿里云百炼: {@code usage.prompt_tokens_details.cached_tokens}
 *       → Spring AI {@code Usage.getCacheReadInputTokens()} 已解析</li>
 *   <li>DeepSeek: {@code usage.prompt_cache_hit_tokens}(usage 顶级字段,非标准)
 *       → OpenAI SDK 放入 {@code CompletionUsage._additionalProperties()},
 *       Spring AI 的 metadata 构建器将其展平到 key-value map 中</li>
 *   <li>其他厂商: 可能用自定义字段名,通过 metadata 额外字段兜底</li>
 * </ul>
 *
 * <p>提取策略(按优先级):
 * <ol>
 *   <li>标准 OpenAI 字段: {@code Usage.getCacheReadInputTokens()}</li>
 *   <li>DeepSeek 自定义字段: metadata key {@code "prompt_cache_hit_tokens"}</li>
 *   <li>其他厂商常见字段: metadata key {@code "cached_tokens"}(部分厂商放顶级)</li>
 * </ol>
 */
public final class CacheTokenExtractor {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CacheTokenExtractor.class);

    private CacheTokenExtractor() {
    }

    /** 厂商自定义缓存命中字段名(按优先级排列,全部尝试)。 */
    private static final String[] VENDOR_CACHE_KEYS = {
            "prompt_cache_hit_tokens",  // DeepSeek
            "cached_tokens",            // 部分厂商放在 usage 顶级
    };

    /**
     * 从 Spring AI Usage + ChatResponseMetadata 中提取缓存命中 token 数。
     *
     * @param usage    Spring AI 的 Usage 对象(可能为 null)
     * @param metadata ChatResponse 的 metadata(可能为 null)
     * @return 缓存命中 token 数,无数据返回 0
     */
    public static long extract(Usage usage, ChatResponseMetadata metadata) {
        // ── debug: 打印 Spring AI 解析出的 usage 全貌 ──
        if (log.isDebugEnabled()) {
            log.debug("[CacheToken] extract 开始 | usage={} | metadata={}",
                    usage == null ? "null" : formatUsage(usage),
                    metadata == null ? "null" : formatMetadata(metadata));
        }

        // 1. 标准 OpenAI 字段(智谱/Kimi/阿里云百炼/OpenAI 官方)
        if (usage != null) {
            Long standard = usage.getCacheReadInputTokens();
            if (log.isDebugEnabled()) {
                log.debug("[CacheToken] 标准 getCacheReadInputTokens() = {}", standard);
            }
            if (standard != null && standard > 0) {
                log.debug("[CacheToken] 命中标准字段,返回 {}", standard);
                return standard;
            }
        }

        // 2. 厂商自定义字段(DeepSeek 等)
        if (metadata != null) {
            for (String key : VENDOR_CACHE_KEYS) {
                Long value = tryGetLong(metadata, key);
                if (log.isDebugEnabled()) {
                    log.debug("[CacheToken] metadata['{}'] = {}", key, value);
                }
                if (value != null && value > 0) {
                    log.debug("[CacheToken] 命中厂商字段 '{}' = {}", key, value);
                    return value;
                }
            }
        }

        log.debug("[CacheToken] 所有来源均无缓存命中数据,返回 0");
        return 0;
    }

    /** 格式化 Spring AI Usage 用于 debug 日志。 */
    private static String formatUsage(Usage u) {
        return "{prompt=" + u.getPromptTokens()
                + ", completion=" + u.getCompletionTokens()
                + ", total=" + u.getTotalTokens()
                + ", cacheRead=" + u.getCacheReadInputTokens()
                + ", cacheWrite=" + u.getCacheWriteInputTokens()
                + ", native=" + u.getNativeUsage()
                + "}";
    }

    /** 格式化 ChatResponseMetadata 的 key 集合用于 debug 日志。 */
    private static String formatMetadata(ChatResponseMetadata m) {
        StringBuilder sb = new StringBuilder("{keys=[");
        boolean first = true;
        for (String key : m.keySet()) {
            if (!first) {
                sb.append(", ");
            }
            String val = String.valueOf(m.get(key));
            sb.append(key).append("=").append(val);
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 从 metadata 中安全提取 long 值(兼容 Long/Integer/Double/String/JsonValue)。 */
    private static Long tryGetLong(ChatResponseMetadata metadata, String key) {
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Integer i) {
            return i.longValue();
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        // 字符串数字(某些厂商可能序列化为字符串)
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        // JsonValue 或其他未知类型:尝试 toString 后 parse
        if (log.isDebugEnabled()) {
            log.debug("[CacheToken] metadata['{}'] 类型未匹配: {} (class={})",
                    key, value, value.getClass().getName());
        }
        return null;
    }
}

