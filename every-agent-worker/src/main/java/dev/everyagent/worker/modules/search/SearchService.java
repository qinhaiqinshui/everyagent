package dev.everyagent.worker.modules.search;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.Sandbox;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.plugin.registry.SearchProviderInvoker;
import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.rpc.RpcDispatcher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一搜索 RPC({@code search},架构 §5.5/§8.5):核心<b>极薄、零类型感知</b>。
 *
 * <p><b>入参(仅四要素)</b>:{@code workspace}(必填,经 {@link WorkspaceManager#resolve} +
 * {@link Sandbox} jailed 到工作区根)/{@code pattern}/{@code kinds?}(可选 string[],缺省 = 全部
 * 已注册 provider)/{@code filters?}(不透明参数袋)。核心<b>不解释</b> {@code pattern} 与
 * {@code filters} 的任何内容,原样透传给各 provider。
 *
 * <p><b>核心行为</b>(无任何硬编码搜索类型名/过滤字段名):「遍历 {@link SearchProviderRegistry}
 * (若有 {@code kinds} 则按 provider 自身声明的 kind 数据过滤)→ 逐个 {@link SearchProvider#search}
 * 触发 → 按 {@code kind}+位置键去重聚合」。类型清单完全来自注册表(内置 provider + 插件 provider)。
 *
 * <p><b>应答</b>:{@code {matchCount, truncated, items:[...]}},{@code truncated} = 各 provider
 * 自身触顶结果的「或」(核心不按 maxResults 裁剪、无全局上限);大结果走 {@code rpc.data} 分批 +
 * 末帧 {@code ok} 汇总(口径同 fs.read,§5.4)。
 *
 * <p><b>护栏</b>:单个 provider 抛异常/超出超时预算(非客户端可见错误)仅 WARN 跳过,不影响其余
 * 结果与应答;{@code kinds} 过滤后无 provider 可跑(或注册表为空)时报可读错误。
 */
@Component
public class SearchService {

    private final WorkspaceManager workspaces;
    private final SearchProviderRegistry searchProviders;
    private final WorkerProperties.Search searchCfg;

    /** 当前生效的 provider 超时预算(ms);≤ 0 = 不限时(仅异常护栏)。包级 setter 供单测注入。 */
    private long providerTimeoutMs;

    void setProviderTimeoutMs(long providerTimeoutMs) {
        this.providerTimeoutMs = providerTimeoutMs;
    }

    public SearchService(RpcDispatcher dispatcher, WorkspaceManager workspaces,
            SearchProviderRegistry searchProviders, WorkerProperties props) {
        this.workspaces = workspaces;
        this.searchProviders = searchProviders;
        this.searchCfg = props == null ? new WorkerProperties.Search() : props.getSearch();
        this.providerTimeoutMs = searchCfg.getProviderTimeoutMs();
        dispatcher.register(RpcMethods.SEARCH, this::search);
    }

    // ---- RPC 入口 ----

    private void search(RpcContext ctx) throws IOException {
        // jailed:workspace 必填,resolve 内完成绝对路径 + realpath + 非系统目录校验(与 fs.read 同源)
        Sandbox sb = new Sandbox(workspaces.resolve(ctx.strParam("workspace")));
        Path root = sb.root();
        String pattern = ctx.strParam("pattern");
        List<String> kinds = optStringList(ctx, "kinds");
        Map<String, Object> filters = optObjectMap(ctx, "filters");
        String workspaceId = workspaces.idOfRoot(root.toString());

        List<SearchProvider> providers = searchProviders.getProviders();
        if (!kinds.isEmpty()) {
            providers = providers.stream().filter(p -> servesAny(p.kinds(), kinds)).toList();
        }
        if (providers.isEmpty()) {
            throw new BadParamsException("没有可用的搜索提供者(搜索类型注册表为空或 kinds 无匹配): " + kinds);
        }

        SearchProvider.SearchRequest req = new SearchProvider.SearchRequest(
                workspaceId, root, pattern, filters, kinds);
        ArrayNode items = Json.arr();
        Set<String> seen = new HashSet<>();
        boolean truncated = false;
        // 护栏(§8.5):单 provider 抛异常/超时仅 WARN 跳过;客户端可见错误(参数/越界/引擎不可用)直接上抛
        SearchProviderInvoker invoker = new SearchProviderInvoker("search", providerTimeoutMs);
        for (SearchProvider provider : providers) {
            SearchProvider.SearchResult result = invoker.invoke(provider, () -> provider.search(req));
            if (result == null || result.items() == null) {
                continue; // 护栏跳过(异常/超时)或 provider 合法返回空
            }
            for (SearchProvider.SearchResultItem item : result.items()) {
                if (item == null) {
                    continue;
                }
                String kind = kindOf(item, provider);
                String positionKey = item.positionKey() == null ? "" : item.positionKey();
                if (!seen.add(kind + "\u0000" + positionKey)) {
                    continue; // 同 kind 同位置去重(跨 kind 不判重)
                }
                items.add(render(item, kind, provider.id()));
            }
            if (result.truncated()) {
                truncated = true;
            }
        }
        reply(ctx, items, truncated);
    }

    // ---- 参数读取(仅核心契约四要素)----

    /** {@code kinds?}:可选 string[];缺省/空 = 不传 = 全部已注册 provider 都跑。 */
    private static List<String> optStringList(RpcContext ctx, String name) {
        JsonNode n = ctx.params().path(name);
        if (n.isMissingNode() || n.isNull() || !n.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode e : n) {
            String s = e.asString("");
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    /** {@code filters?}:不透明参数袋(key→value);核心只透传,绝不解释其 key/value。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> optObjectMap(RpcContext ctx, String name) {
        JsonNode n = ctx.params().path(name);
        if (n.isMissingNode() || n.isNull() || !n.isObject()) {
            return Map.of();
        }
        Map<String, Object> m = Json.convert(n, Map.class);
        return m == null ? Map.of() : m;
    }

    // ---- 聚合 ----

    /** provider 声明的 kind 集合与请求 kinds 是否有交集(数据驱动过滤,核心不解释 kind 语义)。 */
    private static boolean servesAny(Set<String> declared, List<String> requested) {
        return declared != null && !Collections.disjoint(declared, requested);
    }

    /** 结果项 kind:自带非空则用;否则回退 provider 声明的首个 kind(核心不硬编码任何类型名)。 */
    private static String kindOf(SearchProvider.SearchResultItem item, SearchProvider provider) {
        String kind = item.kind();
        if (kind != null && !kind.isBlank()) {
            return kind;
        }
        return provider.kinds().stream().findFirst().orElse("unknown");
    }

    /**
     * 结果项 → wire 形状:{@code kind}/{@code providerId}(自带则尊重、否则填 provider.id())/
     * {@code score}(仅自带时携带) + 字段袋原样展开。核心不解释字段语义。
     */
    private static ObjectNode render(SearchProvider.SearchResultItem item, String kind, String fallbackProviderId) {
        ObjectNode o = Json.obj();
        o.put("kind", kind);
        o.put("providerId", SearchProvider.providerIdOr(item.providerId(), fallbackProviderId));
        if (item.score() != null) {
            o.put("score", item.score());
        }
        if (item.fields() != null) {
            for (Map.Entry<String, Object> e : item.fields().entrySet()) {
                o.set(e.getKey(), Json.toJson(e.getValue()));
            }
        }
        return o;
    }

    /**
     * 应答:结果序列化后 ≤单帧内联上限({@code worker.search.inline-max-bytes})直接内联 ok;
     * 超限按结果项边界切批 {@code rpc.data} 回传({@code worker.search.chunk-bytes};批项 = 完整
     * 结果项,不撕裂),末帧 ok 只带汇总。
     */
    private void reply(RpcContext ctx, ArrayNode items, boolean truncated) {
        int matchCount = items.size();
        ObjectNode full = Json.obj().put("matchCount", matchCount).put("truncated", truncated);
        full.set("items", items);
        if (Json.write(full).getBytes(StandardCharsets.UTF_8).length <= searchCfg.getInlineMaxBytes()) {
            ctx.ok(full);
            return;
        }
        List<JsonNode> batch = new ArrayList<>();
        int bytes = 0;
        for (JsonNode item : items) {
            int size = Json.write(item).getBytes(StandardCharsets.UTF_8).length;
            if (!batch.isEmpty() && bytes + size > searchCfg.getChunkBytes()) {
                ctx.data(List.copyOf(batch), true);
                batch = new ArrayList<>();
                bytes = 0;
            }
            batch.add(item);
            bytes += size;
        }
        ctx.data(List.copyOf(batch), false);
        ctx.ok(Json.obj()
                .put("matchCount", matchCount)
                .put("truncated", truncated)
                .put("itemCount", matchCount));
    }
}