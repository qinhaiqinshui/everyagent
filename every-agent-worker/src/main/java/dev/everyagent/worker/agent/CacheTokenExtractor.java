package dev.everyagent.worker.agent;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 从模型返回的 usage 中提取缓存命中 token 数(适配器模式)。
 *
 * <p>不同厂商的 OpenAI 兼容 API 返回缓存命中 token 的字段各不相同:
 * <ul>
 *   <li>OpenAI / 智谱 / Kimi / 阿里云百炼: {@code usage.prompt_tokens_details.cached_tokens}
 *       → Spring AI {@code Usage.getCacheReadInputTokens()} 已解析(但流式模式下
 *       OpenAI SDK 的 CompletionUsage.toString() 不含 promptTokensDetails,
 *       getCacheReadInputTokens() 返回 null → 需要从 native usage 原始 JSON 兜底)</li>
 *   <li>DeepSeek: {@code usage.prompt_cache_hit_tokens}(usage 顶级字段,非标准)</li>
 * </ul>
 *
 * <p>提取策略(按优先级):
 * <ol>
 *   <li>Spring AI 标准字段: {@code Usage.getCacheReadInputTokens()}</li>
 *   <li>native usage 原始对象: 从 CompletionUsage.promptTokensDetails().cachedTokens() 提取
 *       (绕过 Spring AI 的 getDefaultUsage 中间层,直接读 SDK 原始解析结果)</li>
 *   <li>DeepSeek 自定义字段: native usage additionalProperties["prompt_cache_hit_tokens"]</li>
 *   <li>metadata 额外字段: metadata["prompt_cache_hit_tokens"] / metadata["cached_tokens"]</li>
 * </ol>
 */
public final class CacheTokenExtractor {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CacheTokenExtractor.class);

    private CacheTokenExtractor() {
    }

    /** 厂商自定义缓存命中字段名(按优先级排列,全部尝试)。 */
    private static final String[] VENDOR_CACHE_KEYS = {
            "prompt_cache_hit_tokens",  // DeepSeek(usage 顶级)
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
        if (log.isDebugEnabled()) {
            log.debug("[CacheToken] extract 开始 | usage={} | metadata={}",
                    usage == null ? "null" : formatUsage(usage),
                    metadata == null ? "null" : formatMetadata(metadata));
        }

        // 1. Spring AI 标准字段( getDefaultUsage 已提取时直接返回)
        if (usage != null) {
            Long standard = usage.getCacheReadInputTokens();
            if (log.isDebugEnabled()) {
                log.debug("[CacheToken] 标准 getCacheReadInputTokens() = {}", standard);
            }
            if (standard != null && standard > 0) {
                return standard;
            }
        }

        // 2. 从 native usage 原始对象提取(绕过 Spring AI 中间层)
        //    Spring AI getDefaultUsage() 从 CompletionUsage.promptTokensDetails().cachedTokens() 提取,
        //    但流式聚合后 CompletionUsage 的 promptTokensDetails 可能为 empty(toString 不含该字段)。
        //    直接从 native 对象尝试反射读取,确认 SDK 是否解析了该字段。
        if (usage != null) {
            long fromNative = extractFromNativeUsage(usage.getNativeUsage());
            if (fromNative > 0) {
                log.debug("[CacheToken] 从 native usage 提取到 cacheRead={}", fromNative);
                return fromNative;
            }
        }

        // 3. metadata 额外字段(DeepSeek 等: Spring AI from() 把 ChatCompletion._additionalProperties 展平到 metadata)
        if (metadata != null) {
            for (String key : VENDOR_CACHE_KEYS) {
                Long value = tryGetLong(metadata, key);
                if (log.isDebugEnabled()) {
                    log.debug("[CacheToken] metadata['{}'] = {}", key, value);
                }
                if (value != null && value > 0) {
                    log.debug("[CacheToken] 命中 metadata 字段 '{}' = {}", key, value);
                    return value;
                }
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("[CacheToken] 所有来源均无缓存命中数据,返回 0");
        }
        return 0;
    }

    /**
     * 从 Spring AI Usage.getNativeUsage() 返回的原始对象中提取缓存命中 token。
     *
     * <p>native 对象类型取决于实现:
     * <ul>
     *   <li>DefaultUsage.getNativeUsage() → null(4 参数构造器)或原始 CompletionUsage(6 参数构造器)</li>
     *   <li>流式聚合后的 DefaultUsage 通常 nativeUsage=null(getCumulativeUsage 传 null)</li>
     * </ul>
     *
     * <p>当 nativeUsage 为 null 时无法提取——这正是流式模式下缓存丢失的根因:
     * Spring AI 的 UsageCalculator.getCumulativeUsage() 聚合时用 {@code new DefaultUsage(in, out, total, null, cacheRead, cacheWrite)}
     * 构造,nativeUsage 传 null。如果原始 chunk 的 DefaultUsage.getCacheReadInputTokens() 已为 null
     * (因为 CompletionUsage.promptTokensDetails() 在流式 chunk 中为 empty),则聚合后也为 null。
     */
    @SuppressWarnings("unchecked")
    private static long extractFromNativeUsage(Object nativeUsage) {
        if (nativeUsage == null) {
            if (log.isDebugEnabled()) {
                log.debug("[CacheToken] nativeUsage = null(流式聚合后丢失原始对象)");
            }
            return 0;
        }
        if (log.isDebugEnabled()) {
            log.debug("[CacheToken] nativeUsage class={} toString={}", nativeUsage.getClass().getName(), nativeUsage);
        }

        // 尝试从 CompletionUsage 提取 promptTokensDetails.cachedTokens
        try {
            // CompletionUsage.promptTokensDetails() returns Optional<PromptTokensDetails>
            Method promptTokensDetails = nativeUsage.getClass().getMethod("promptTokensDetails");
            Optional<?> ptdOpt = (Optional<?>) promptTokensDetails.invoke(nativeUsage);
            if (ptdOpt != null && ptdOpt.isPresent()) {
                Object ptd = ptdOpt.get();
                if (log.isDebugEnabled()) {
                    log.debug("[CacheToken] promptTokensDetails present: {}", ptd);
                }
                // PromptTokensDetails.cachedTokens() returns Optional<Long>
                Method cachedTokens = ptd.getClass().getMethod("cachedTokens");
                Optional<Long> cachedOpt = (Optional<Long>) cachedTokens.invoke(ptd);
                if (cachedOpt != null && cachedOpt.isPresent()) {
                    long val = cachedOpt.get();
                    log.debug("[CacheToken] promptTokensDetails.cachedTokens() = {}", val);
                    return val;
                }
            }
        } catch (NoSuchMethodException e) {
            // 不是 CompletionUsage 类型,跳过
            if (log.isDebugEnabled()) {
                log.debug("[CacheToken] nativeUsage 无 promptTokensDetails 方法(class={})", nativeUsage.getClass().getName());
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[CacheToken] 反射读取 promptTokensDetails 失败", e);
            }
        }

        // 尝试从 CompletionUsage._additionalProperties() 提取 DeepSeek 的 prompt_cache_hit_tokens
        try {
            Method addlProps = nativeUsage.getClass().getMethod("_additionalProperties");
            Object props = addlProps.invoke(nativeUsage);
            if (props instanceof java.util.Map<?, ?> map) {
                for (String key : VENDOR_CACHE_KEYS) {
                    Object val = map.get(key);
                    if (log.isDebugEnabled()) {
                        log.debug("[CacheToken] native._additionalProperties['{}'] = {}", key, val);
                    }
                    Long parsed = parseValue(val);
                    if (parsed != null && parsed > 0) {
                        return parsed;
                    }
                }
            }
        } catch (NoSuchMethodException e) {
            // 不是 CompletionUsage 类型
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[CacheToken] 反射读取 _additionalProperties 失败", e);
            }
        }

        return 0;
    }

    /** 尝试把任意值解析为 Long(兼容 Long/Integer/Double/JsonValue 等)。 */
    private static Long parseValue(Object value) {
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
        // JsonValue: 尝试调用 asLongOrThrow
        try {
            Method asLong = value.getClass().getMethod("asLongOrThrow");
            return (Long) asLong.invoke(value);
        } catch (NoSuchMethodException e) {
            // 不是 JsonValue
        } catch (Exception e) {
            // 调用失败
        }
        return null;
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
        return parseValue(value);
    }
}


