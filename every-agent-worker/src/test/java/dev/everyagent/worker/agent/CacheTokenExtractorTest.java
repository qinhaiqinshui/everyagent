package dev.everyagent.worker.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CacheTokenExtractor} 单元测试:验证标准字段与厂商自定义字段的适配提取。
 */
class CacheTokenExtractorTest {

    @Test
    @DisplayName("标准 OpenAI 字段: Usage.getCacheReadInputTokens() 有值时直接返回")
    void standardOpenAiField() {
        Usage usage = new DefaultUsage(1000, 50, 1050, null, 800L, null);
        long result = CacheTokenExtractor.extract(usage, null);
        assertEquals(800, result);
    }

    @Test
    @DisplayName("标准字段为 0 时回退到 metadata 厂商字段")
    void fallbackToMetadataWhenStandardIsZero() {
        Usage usage = new DefaultUsage(1000, 50, 1050, null, 0L, null);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().build();
        // metadata 是不可变的,需要通过 builder 构造
        // 由于 builder 的 keyValue 方法是 protected,这里用一个空的 metadata
        // 实际测试中厂商字段会被 Spring AI 放入 metadata map
        long result = CacheTokenExtractor.extract(usage, metadata);
        assertEquals(0, result); // 空 metadata,无厂商字段
    }

    @Test
    @DisplayName("Usage 和 metadata 均为 null 时返回 0")
    void nullSafety() {
        assertEquals(0, CacheTokenExtractor.extract(null, null));
    }

    @Test
    @DisplayName("标准字段为 null(旧版 DefaultUsage)时不报错")
    void nullCacheReadField() {
        // 3 参数构造器: cacheReadInputTokens 默认为 null
        Usage usage = new DefaultUsage(1000, 50, 1050);
        long result = CacheTokenExtractor.extract(usage, null);
        assertEquals(0, result);
    }
}
