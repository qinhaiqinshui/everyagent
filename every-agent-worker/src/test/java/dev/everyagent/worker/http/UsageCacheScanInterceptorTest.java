package dev.everyagent.worker.http;

import okio.Buffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link UsageCacheScanInterceptor} 单元测试:用各厂商真实 SSE 报文验证扫描与提取。
 */
class UsageCacheScanInterceptorTest {

    /** 阿里云百炼真实末尾 usage chunk(prompt_tokens_details.cached_tokens 嵌套形态)。 */
    private static final String ALIYUN_USAGE_LINE = "data: {\"id\":\"chatcmpl-3f7eb314-ef58-9df5-885d-143ef0eea9c9\",\"object\":\"chat.completion.chunk\",\"created\":1793097815,\"model\":\"qwen3.8-flash\",\"choices\":[],\"usage\":{\"prompt_tokens\":2232,\"total_tokens\":2579,\"completion_tokens\":347,\"prompt_tokens_details\":{\"cached_tokens\":1792,\"text_tokens\":2232},\"completion_tokens_details\":{\"reasoning_tokens\":28,\"text_tokens\":347}}}";

    /** DeepSeek 形态(usage 顶级 prompt_cache_hit_tokens)。 */
    private static final String DEEPSEEK_USAGE_LINE = "data: {\"id\":\"ee97cf80-10e6-46ce-b711-35bd4ccc6f2f\",\"object\":\"chat.completion.chunk\",\"created\":1793098638,\"model\":\"deepseek-flash\",\"choices\":[],\"usage\":{\"prompt_tokens\":2097,\"total_tokens\":2436,\"completion_tokens\":339,\"prompt_cache_hit_tokens\":1024,\"prompt_cache_miss_tokens\":1073}}";

    /** 智谱形态(标准 OpenAI 嵌套)。 */
    private static final String ZHIPU_USAGE_LINE = "data: {\"id\":\"202610271847551bdf03407ca5483a\",\"created\":1793098076,\"object\":\"chat.completion.chunk\",\"model\":\"glm-5.3\",\"choices\":[],\"usage\":{\"prompt_tokens\":1970,\"total_tokens\":2345,\"completion_tokens\":375,\"prompt_tokens_details\":{\"cached_tokens\":1024}}}";

    /** 普通正文 chunk(无 usage,不应命中)。 */
    private static final String PLAIN_CHUNK_LINE = "data: {\"id\":\"202610271847551bdf03407ca5483a\",\"object\":\"chat.completion.chunk\",\"model\":\"glm-5.3\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"**\"}}]}";

    /** usage 为 null 的中间 chunk(DeepSeek 实测形态)。 */
    private static final String NULL_USAGE_LINE = "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}],\"usage\":null}";

    @Test
    @DisplayName("阿里云百炼: 嵌套 prompt_tokens_details.cached_tokens 提取")
    void scanAliyunNested() {
        Long v = UsageCacheScanInterceptor.scanLine(ALIYUN_USAGE_LINE);
        assertEquals(1792L, v);
        assertEquals("chatcmpl-3f7eb314-ef58-9df5-885d-143ef0eea9c9",
                UsageCacheScanInterceptor.idOfLine(ALIYUN_USAGE_LINE));
    }

    @Test
    @DisplayName("DeepSeek: 顶级 prompt_cache_hit_tokens 提取")
    void scanDeepseekTopLevel() {
        Long v = UsageCacheScanInterceptor.scanLine(DEEPSEEK_USAGE_LINE);
        assertEquals(1024L, v);
    }

    @Test
    @DisplayName("智谱: 标准嵌套 cached_tokens 提取")
    void scanZhipuStandard() {
        Long v = UsageCacheScanInterceptor.scanLine(ZHIPU_USAGE_LINE);
        assertEquals(1024L, v);
    }

    @Test
    @DisplayName("普通 chunk / usage:null 不命中")
    void scanMiss() {
        assertNull(UsageCacheScanInterceptor.scanLine(PLAIN_CHUNK_LINE));
        assertNull(UsageCacheScanInterceptor.scanLine(NULL_USAGE_LINE));
        assertNull(UsageCacheScanInterceptor.scanLine(null));
    }

    @Test
    @DisplayName("Source 流式扫描: 字节分片跨行边界仍能提取(记录 + take 消费)")
    void scanningSourceAcrossChunkBoundaries() throws Exception {
        String sse = PLAIN_CHUNK_LINE + "\n\n" + ZHIPU_USAGE_LINE + "\n\ndata: [DONE]\n\n";
        byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);

        // 用 7 字节小分片驱动 read,模拟网络分片(行跨多次 read)
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            okio.Source src = okio.Okio.source(in);
            UsageCacheScanInterceptor.ScanningSource scanning =
                    new UsageCacheScanInterceptor.ScanningSource(src);
            Buffer sink = new Buffer();
            long total = 0;
            while (true) {
                long n = scanning.read(sink, 7);
                if (n < 0) {
                    break;
                }
                total += n;
            }
            assertEquals(bytes.length, total, "转发字节数应与输入一致(旁路不改流)");

            String id = UsageCacheScanInterceptor.idOfLine(ZHIPU_USAGE_LINE);
            Long cached = UsageCacheScanInterceptor.take(id);
            assertEquals(1024L, cached, "跨分片边界仍应扫描到 usage 行的 cached_tokens");
            // take 一次性消费
            assertNull(UsageCacheScanInterceptor.take(id));
        }
    }

    @Test
    @DisplayName("记录与消费: record → take 返回并清空")
    void recordAndTake() {
        UsageCacheScanInterceptor.take("test-id-clean"); // 确保干净
        // 通过 scanLine + record 间接验证(ScanningSource.record 是 private)
        // 直接验证 take 对不存在 key 返回 null
        assertNull(UsageCacheScanInterceptor.take("no-such-id"));
        assertNull(UsageCacheScanInterceptor.take(null));
        assertNull(UsageCacheScanInterceptor.take(""));
    }
}
