package dev.everyagent.worker.http;

import okhttp3.Interceptor;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SSE 流缓存命中 token 扫描拦截器(架构 §5.2:旁路提取,不修改任何响应内容)。
 *
 * <p><b>为什么需要本拦截器</b>:OpenAI 兼容厂商(阿里云百炼/智谱/商汤/DeepSeek 等)在
 * 流式响应末尾 chunk 的 {@code usage} 里携带缓存命中 token,字段名各异
 * ({@code prompt_tokens_details.cached_tokens} / {@code prompt_cache_hit_tokens})。
 * 实测(2026-10)Spring AI 2.0.1 + openai-java 4.49.0 的流式链路会丢失该字段
 * (CompletionUsage 解析后 promptTokensDetails 为 missing,详见
 * {@link dev.everyagent.worker.agent.CacheTokenExtractor} 的排查记录)——
 * 故在 HTTP 层直接扫描<b>原始 SSE 字节流</b>提取,绕过 SDK/Spring AI 的解析。
 *
 * <p><b>工作方式</b>:包装响应 body 的 Source,字节流经时滚动缓冲最近文本,
 * 对完整的 {@code data: {...}} SSE 行做正则扫描;命中缓存字段时以该行 JSON 的
 * {@code id} 为 key 存入静态表(每响应的末尾 usage chunk 与普通 chunk 同 id,
 * 同一 HTTP 响应内天然聚簇)。消费方
 * ({@code CacheTokenExtractor}/{@code WorkerToolEventAdvisor})在 doAfterStream
 * 用 {@link #take(String)} 按 ChatResponse metadata id 查询并移除。
 *
 * <p><b>清理</b>:take() 即移除(一次消费);未消费的残留(流中断/异常)由容量
 * 上限触发全表重建兜底(扫描场景 key 数量 = 并发响应数,远小于上限,重建代价可忽略)。
 *
 * <p>设计纪律:纯旁路(extract only)——包装 Source 逐字节原样转发,扫描失败/正则不命中
 * 不影响流;线程安全(ConcurrentHashMap + Source 单读线程写、take 多线程读单 key)。
 */
public final class UsageCacheScanInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(UsageCacheScanInterceptor.class);

    /** 共享单例(与 HttpRequestLoggingInterceptor 同生命周期,挂在每个模型 OkHttp client 上)。 */
    public static final UsageCacheScanInterceptor INSTANCE = new UsageCacheScanInterceptor();

    private UsageCacheScanInterceptor() {
    }

    /** 响应 id → 缓存命中 token 数(扫描写,take 读后删)。 */
    private static final ConcurrentHashMap<String, Long> CACHE_BY_ID = new ConcurrentHashMap<>();

    /** 容量兜底:超限全表重建(正常并发响应数远低于该值;防泄漏保险丝)。 */
    private static final int MAX_ENTRIES = 512;

    /**
     * 按 ChatResponse 的 id 取走缓存命中 token 数(一次性消费)。
     *
     * @param responseId ChatResponse.metadata.id(= 响应 chunk JSON 的 id 字段)
     * @return 缓存命中 token 数;无记录返回 null(厂商未返回/未命中/非流式)
     */
    public static Long take(String responseId) {
        if (responseId == null || responseId.isEmpty()) {
            return null;
        }
        Long v = CACHE_BY_ID.remove(responseId);
        if (log.isDebugEnabled()) {
            log.debug("[CacheToken] scan.take(id={}) = {}", responseId, v);
        }
        return v;
    }

    /** 记录扫描结果(容量兜底后写入)。 */
    private static void record(String id, long cached) {
        if (id == null || id.isEmpty() || cached <= 0) {
            return;
        }
        if (CACHE_BY_ID.size() >= MAX_ENTRIES) {
            // 保险丝:并发响应数异常堆积(泄漏)时宁可全清重扫,不无限增长
            CACHE_BY_ID.clear();
        }
        Long prev = CACHE_BY_ID.put(id, cached);
        if (log.isDebugEnabled() && (prev == null || prev != cached)) {
            log.debug("[CacheToken] scan.record(id={}, cachedTokens={}) prev={}", id, cached, prev);
        }
    }

    // ── SSE 扫描正则(操作 data: 行 JSON 原文) ──

    /**
     * 缓存命中字段正则(两种厂商形态,任一命中即记录):
     * <ul>
     *   <li>{@code "prompt_tokens_details":{"cached_tokens":1792,...}}
     *       —— OpenAI/阿里云百炼/智谱/Kimi(嵌套 details)</li>
     *   <li>{@code "prompt_cache_hit_tokens":1024} —— DeepSeek(usage 顶级)</li>
     * </ul>
     */
    private static final Pattern CACHED_PATTERN = Pattern.compile(
            "\"prompt_tokens_details\"\\s*:\\s*\\{[^{}]*?\"cached_tokens\"\\s*:\\s*(\\d+)"
                    + "|\"prompt_cache_hit_tokens\"\\s*:\\s*(\\d+)");

    /** SSE data 行的 id 字段(取行内 JSON 的 id 作关联 key)。 */
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    /** usage 字段存在判定(只对含 usage 的行做缓存扫描,减少正则开销)。 */
    private static final Pattern USAGE_LINE_HINT = Pattern.compile("\"usage\"\\s*:\\s*\\{");

    /** 行级扫描:对一条完整 SSE data 行提取缓存命中数(无则 null)。 */
    static Long scanLine(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        // 快速预判:usage 行才可能带缓存字段(末尾 usage chunk: "usage":{...} 非 null)
        if (!USAGE_LINE_HINT.matcher(line).find()) {
            return null;
        }
        Matcher m = CACHED_PATTERN.matcher(line);
        Long best = null;
        while (m.find()) {
            String g1 = m.group(1);
            String g2 = m.group(2);
            long v = g1 != null ? Long.parseLong(g1) : (g2 != null ? Long.parseLong(g2) : 0);
            if (v > 0) {
                best = v;
            }
        }
        return best;
    }

    /** 从 SSE data 行提取 id 字段。 */
    static String idOfLine(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        Matcher m = ID_PATTERN.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        // 只包装 SSE 流式响应(text/event-stream);非流式 JSON 由 SDK 正常解析
        // (非流式 getDefaultUsage 的 native 链路完整,无需旁路)。
        String contentType = response.header("Content-Type");
        if (response.body() == null || contentType == null
                || !contentType.contains("text/event-stream")) {
            return response;
        }
        okhttp3.ResponseBody body = response.body();
        return response.newBuilder()
                .body(ResponseBody.create(
                        Okio.buffer(new ScanningSource(body.source())),
                        body.contentType(), body.contentLength()))
                .build();
    }

    /**
     * 逐字节转发 + 滚动行扫描的 Source。
     *
     * <p>实现:read 的字节写入旁路 buffer,按完整行(\n)切出后扫描;行可能跨 read 边界,
     * 未完成的尾部留在 buffer 等下一段拼接(SSE 行通常 &lt; 16KB,滚动上限 256KB 防异常长行)。
     * 扫描异常一律吞掉(纯旁路,绝不影响流的转发)。
     */
    static final class ScanningSource extends ForwardingSource {

        /** 旁路滚动缓冲(只增不消费原始流)。 */
        private final java.io.ByteArrayOutputStream tail = new java.io.ByteArrayOutputStream();

        ScanningSource(Source delegate) {
            super(delegate);
        }

        @Override
        public long read(okio.Buffer sink, long byteCount) throws IOException {
            long result = super.read(sink, byteCount);
            try {
                if (result > 0) {
                    // 把本次读到的字节快照进旁路 buffer(sink 从 readPos 起的这 result 字节)
                    okio.Buffer snapshot = sink.clone();
                    snapshot.skip(sink.size() - result);
                    byte[] bytes = snapshot.readByteArray();
                    tail.write(bytes);
                    scanBuffer();
                }
            } catch (RuntimeException e) {
                if (log.isDebugEnabled()) {
                    log.debug("[CacheToken] 扫描异常(忽略,不影响流)", e);
                }
            }
            return result;
        }

        /** 按完整行扫描旁路缓冲;行尾后丢弃已扫描前缀,保留未完行。 */
        private void scanBuffer() {
            byte[] all = tail.toByteArray();
            String text = new String(all, StandardCharsets.UTF_8);
            int searched = 0;
            int nl;
            while ((nl = text.indexOf('\n', searched)) >= 0) {
                String line = text.substring(searched, nl);
                searched = nl + 1;
                scanOneLine(line);
            }
            // 保留未完成行(可能跨 read);超限截断防异常长行无限增长
            String rest = text.substring(searched);
            if (rest.length() > 262_144) {
                rest = rest.substring(rest.length() - 262_144);
            }
            tail.reset();
            tail.write(rest.getBytes(StandardCharsets.UTF_8), 0, rest.length());
        }

        /** 单行扫描:usage 行 → 提取缓存值 + id → record。 */
        private void scanOneLine(String line) {
            try {
                Long cached = scanLine(line);
                if (cached != null && cached > 0) {
                    String id = idOfLine(line);
                    if (id != null) {
                        record(id, cached);
                    } else if (log.isDebugEnabled()) {
                        log.debug("[CacheToken] 扫描到 cachedTokens={} 但行内无 id,丢弃: {}",
                                cached, abbreviate(line));
                    }
                }
            } catch (RuntimeException e) {
                if (log.isDebugEnabled()) {
                    log.debug("[CacheToken] 单行扫描异常(忽略): {}", abbreviate(line), e);
                }
            }
        }

        private static String abbreviate(String s) {
            if (s == null) {
                return "";
            }
            return s.length() <= 200 ? s : s.substring(0, 200) + "…";
        }
    }
}
