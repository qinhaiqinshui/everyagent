package dev.everyagent.worker.agent;

import dev.everyagent.worker.http.UsageCacheScanInterceptor;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 从模型返回的 usage 中提取缓存命中 token 数(适配器模式)。
 *
 * <p>厂商返回缓存命中 token 的字段形态各异:
 * <ul>
 *   <li>OpenAI / 智谱 / Kimi / 阿里云百炼: {@code usage.prompt_tokens_details.cached_tokens}(嵌套)</li>
 *   <li>DeepSeek: {@code usage.prompt_cache_hit_tokens}(usage 顶级)</li>
 * </ul>
 *
 * <p><b>流式链路背景(2026-10 实测)</b>:Spring AI 2.0.1 的流式路径把 usage 转成只含
 * {promptTokens, totalTokens, completionTokens} 三字段的 HashMap(nativeUsage 即该 Map),
 * {@code prompt_tokens_details} 在转换时即被丢弃——SDK 对象与缓存字段到不了 usage 提取层。
 * 故主提取路径是 {@link UsageCacheScanInterceptor} 在 OkHttp Source 层对原始 SSE 字节流的
 * 旁路扫描(以响应 id 为 key,本类经 {@link UsageCacheScanInterceptor#take} 消费)。
 *
 * <p>提取策略(按优先级):
 * <ol>
 *   <li>SSE 扫描表(流式主路径,按 metadata.id 一次性消费)</li>
 *   <li>Spring AI 标准字段 {@code Usage.getCacheReadInputTokens()}(非流式/SDK 正常解析)</li>
 *   <li>native usage: HashMap 嵌套 key / openai SDK CompletionUsage 反射</li>
 *   <li>metadata 额外字段(SDK 把 additionalProperties 展平到 metadata)</li>
 * </ol>
 */
public final class CacheTokenExtractor {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CacheTokenExtractor.class);

    private CacheTokenExtractor() {
    }

    /** 厂商自定义缓存命中字段名(顶级形态)。 */
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
        long result = doExtract(usage, metadata);
        if (log.isDebugEnabled()) {
            log.debug("[CacheToken] extract result={} (metadata.id={})",
                    result, metadata == null ? "null" : metadata.getId());
        }
        return result;
    }

    private static long doExtract(Usage usage, ChatResponseMetadata metadata) {
        // 1. HTTP 层 SSE 原始流扫描结果(流式主路径:绕过 SDK/Spring AI 解析,
        //    UsageCacheScanInterceptor 在 OkHttp source 层直接扫原始 JSON 字节;
        //    key = ChatResponse.metadata.id,与响应 chunk JSON 的 id 同值,一次性消费)
        if (metadata != null && metadata.getId() != null && !metadata.getId().isEmpty()) {
            Long scanned = UsageCacheScanInterceptor.take(metadata.getId());
            if (scanned != null && scanned > 0) {
                return scanned;
            }
        }

        // 2. Spring AI 标准字段(非流式/SDK 正常解析时可用)
        if (usage != null) {
            Long standard = usage.getCacheReadInputTokens();
            if (standard != null && standard > 0) {
                return standard;
            }
        }

        // 3. native usage(HashMap 精简形态 / SDK CompletionUsage 反射)
        if (usage != null) {
            long fromNative = extractFromNativeUsage(usage.getNativeUsage());
            if (fromNative > 0) {
                return fromNative;
            }
        }

        // 4. metadata 额外字段(SDK 把 additionalProperties 展平到 metadata)
        if (metadata != null) {
            for (String key : VENDOR_CACHE_KEYS) {
                Long value = tryGetLong(metadata, key);
                if (value != null && value > 0) {
                    return value;
                }
            }
        }
        return 0;
    }

    /**
     * 从 Spring AI Usage.getNativeUsage() 返回的原始对象中提取缓存命中 token。
     *
     * <p>native 对象形态(2026-10 实测):
     * <ul>
     *   <li><b>流式: java.util.HashMap</b>,只含三 token key —— 缓存字段已丢(保留
     *       嵌套 key 分支防未来 Spring AI 修复后带全量字段);</li>
     *   <li>非流式/直通: openai SDK CompletionUsage(反射 promptTokensDetails().cachedTokens());</li>
     *   <li>聚合构造: null。</li>
     * </ul>
     */
    private static long extractFromNativeUsage(Object nativeUsage) {
        if (nativeUsage == null) {
            return 0;
        }

        // Map 形态
        if (nativeUsage instanceof java.util.Map<?, ?> map) {
            Object ptd = map.get("promptTokensDetails");
            if (ptd instanceof java.util.Map<?, ?> ptdMap) {
                Long parsed = parseValue(ptdMap.get("cachedTokens"));
                if (parsed != null && parsed > 0) {
                    return parsed;
                }
            }
            for (String key : VENDOR_CACHE_KEYS) {
                Long parsed = parseValue(map.get(key));
                if (parsed != null && parsed > 0) {
                    return parsed;
                }
            }
            return 0;
        }

        // openai SDK CompletionUsage 形态(反射)
        try {
            Method promptTokensDetails = nativeUsage.getClass().getMethod("promptTokensDetails");
            Optional<?> ptdOpt = (Optional<?>) promptTokensDetails.invoke(nativeUsage);
            if (ptdOpt != null && ptdOpt.isPresent()) {
                Method cachedTokens = ptdOpt.get().getClass().getMethod("cachedTokens");
                @SuppressWarnings("unchecked")
                Optional<Long> cachedOpt = (Optional<Long>) cachedTokens.invoke(ptdOpt.get());
                if (cachedOpt != null && cachedOpt.isPresent() && cachedOpt.get() > 0) {
                    return cachedOpt.get();
                }
            }
        } catch (Exception e) {
            // 非 SDK 对象形态,静默跳过(metadata 兜底路径仍在)
            if (log.isDebugEnabled()) {
                log.debug("[CacheToken] native 反射 promptTokensDetails 失败: {}", e.toString());
            }
        }

        // SDK additionalProperties(DeepSeek 顶级字段)
        try {
            Method addlProps = nativeUsage.getClass().getMethod("_additionalProperties");
            Object props = addlProps.invoke(nativeUsage);
            if (props instanceof java.util.Map<?, ?> map) {
                for (String key : VENDOR_CACHE_KEYS) {
                    Long parsed = parseValue(map.get(key));
                    if (parsed != null && parsed > 0) {
                        return parsed;
                    }
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[CacheToken] native 反射 _additionalProperties 失败: {}", e.toString());
            }
        }
        return 0;
    }

    /** 尝试把任意值解析为 Long(兼容 Long/Integer/Number/openai JsonValue)。 */
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
        // openai JsonValue: asLongOrThrow
        try {
            Method asLong = value.getClass().getMethod("asLongOrThrow");
            return (Long) asLong.invoke(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 metadata 中提取厂商顶级字段值。 */
    private static Long tryGetLong(ChatResponseMetadata metadata, String key) {
        return parseValue(metadata.get(key));
    }
}
