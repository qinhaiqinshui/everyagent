package dev.everyagent.worker.slash;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Collator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.everyagent.contract.json.Json;
import dev.everyagent.contract.rpc.Rpc;
import dev.everyagent.worker.modules.Sandbox;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.NotFoundException;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.rpc.RpcDispatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * slash 域 RPC 方法:
 * <ul>
 *   <li>{@code slash.list}:聚合 {@link SlashCommandRegistry},返回全部 `/` 候选项
 *       (含后端构造的 opaque token 作为 insertText,前端零 token 构造逻辑)。</li>
 *   <li>{@code mention.query}:`@` 文件搜索。浏览模式列当前目录顶层;搜索模式广度优先
 *       收集后按匹配质量排序(文件名连续命中 &gt; 路径连续命中 &gt; 子序列;前缀/词首
 *       加成,子序列按跨度扣分),隐藏规则对齐前端
 *       {@code shouldHideWorkspacePath}(/plugins、.git),返回最多 10 条
 *       (用户约束,避免传多余数据)。</li>
 *   <li>{@code slash.select}:按条目 id 触发该条目的 selectHandler(业务 onSelect),循环消费
 *       返回的多个 {@link SlashSelectionResult},把各 result 的 {@code payload.slashId} 注入
 *       返回的 opaque token 后下发(未指定 id 回退父条目 id),响应 {@code { results: [...] }}。</li>
 *   <li>{@code slash.cancel}:用户取消胶囊(底部 ❌ 或内联 ✕)时,有 taskId 先从任务 meta
 *       的 {@code slashTaskTokens} 移除自己的 token,再触发条目 cancelHandler(业务 onCancel)。</li>
 * </ul>
 *
 * <p>路径语义(与前端 pathUtils 一致):{@code path} 为工作区相对路径(空/缺省=根,经
 * workspace 沙箱 jail;交给 AI 的引用形式,无前导 /);{@code fullPath} 为业务绝对路径
 * (前导 /,仅用于前端展示与点击打开,不进入 AI 上下文)。
 */
@Component
public class SlashMethods {

    /** @ 搜索返回条数上限(用户约束:最多 10 个)。 */
    private static final int MENTION_LIMIT = 10;

    /** 搜索模式递归收集节点上限,防超大目录树卡死(超限即截断,仍从已收集里取前 10)。 */
    private static final int MAX_SEARCH_NODES = 2000;

    private static final Logger log = LoggerFactory.getLogger(SlashMethods.class);

    private final SlashCommandRegistry registry;
    private final WorkspaceManager workspaces;
    /** slash 任务级 token 公共存储(slash.taskTokens.apply 写入 token 并经它落盘/广播)。 */
    private final SlashTaskScopeStore scopeStore;

    public SlashMethods(RpcDispatcher dispatcher, SlashCommandRegistry registry,
            WorkspaceManager workspaces, SlashTaskScopeStore scopeStore) {
        this.registry = registry;
        this.workspaces = workspaces;
        this.scopeStore = scopeStore;

        dispatcher.register(RpcMethods.SLASH_LIST, ctx -> ctx.ok(listItems()));
        dispatcher.register(RpcMethods.SLASH_SELECT, this::selectItem);
        dispatcher.register(RpcMethods.SLASH_TASK_TOKENS_APPLY, this::applyTaskToken);
        dispatcher.register(RpcMethods.SLASH_CANCEL, this::cancelItem);
        dispatcher.register(RpcMethods.MENTION_QUERY, this::mentionQuery);
    }

    // ---- slash.list ----

    private ObjectNode listItems() {
        ArrayNode items = Json.arr();
        for (SlashCommandItem item : registry.list()) {
            ObjectNode o = Json.obj();
            o.put("id", item.id());
            o.put("title", item.title());
            if (item.subtitle() != null) {
                o.put("subtitle", item.subtitle());
            }
            if (item.icon() != null) {
                o.put("icon", item.icon());
            }
            if (item.group() != null) {
                o.put("group", item.group());
            }
            o.put("insertText", item.insertText());
            o.put("defaultSelected", item.defaultSelected());
            items.add(o);
        }
        return Json.obj().set("items", items);
    }

    // ---- slash.select ----

    /**
     * slash.select:按 id 反查条目并触发 selectHandler;把返回的各 token 里的 opaque 载荷
     * 注入相应 result 的 slashId(未显式指定 id 时回退父条目 id,幂等)再下发,前端据此
     * 建立「胶囊 ↔ 条目」的归属。单次可返回多个结果(如「无人值守」联动返回双胶囊),
     * 响应 {@code { results: [{id?, token, position}, ...] }} 供前端循环 apply。
     */
    private void selectItem(RpcContext ctx) {
        String id = ctx.strParam("id");
        String taskId = ctx.optStrParam("taskId", null); // 可空=草稿态
        SlashCommandItem item = registry.itemById(id);   // 未知 id → NotFoundException → NOT_FOUND
        List<SlashSelectionResult> results = item.selectHandler().onSelect(item, taskId);
        if (results == null) {
            results = List.of();
        }
        ArrayNode out = Json.arr();
        for (SlashSelectionResult result : results) {
            String resultId = (result.id() != null && !result.id().isEmpty()) ? result.id() : id;
            String token = injectSlashId(result.token(), resultId);
            ObjectNode o = Json.obj();
            o.put("id", resultId);
            o.put("token", token);
            o.put("position", result.position().wireName());
            out.add(o);
        }
        ctx.ok(Json.obj().set("results", out));
    }

    /**
     * 把 slashId 注入 opaque token 的 payload(幂等):非 opaque(纯文本/空/null)原样返回,
     * 已含字符串 slashId 原样返回;否则深拷贝 payload 后 put 并重建 token。
     */
    private static String injectSlashId(String token, String slashId) {
        if (token == null || token.isEmpty()) {
            return token;
        }
        SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(token);
        if (parsed == null) {
            return token;
        }
        if (parsed.payload().has("slashId") && parsed.payload().path("slashId").isTextual()) {
            return token;
        }
        ObjectNode payload = ((ObjectNode) parsed.payload()).deepCopy();
        payload.put("slashId", slashId);
        return SlashTokenEncoder.buildToken(
                parsed.kind(), parsed.label(), parsed.summary(), payload);
    }

    // ---- slash.taskTokens.apply ----

    /**
     * slash.taskTokens.apply:把返回的 bottom token 写入任务级 scope(slash 层公共存储,
     * 字段 slashTaskTokens 仅 slash 层读写),随后调业务 onSelect 让注册方写自己的业务标记
     * (注册方自己实现;异常仅记日志,不阻塞 RPC 应答)。
     * 流程:查条目(未知 id → NotFound)→ 公共存储写入(不存在/无权 → NOT_FOUND)→ 业务 onSelect → ok。
     */
    private void applyTaskToken(RpcContext ctx) {
        String taskId = ctx.strParam("taskId");
        String id = ctx.strParam("id");
        String token = ctx.strParam("token");
        SlashCommandItem item = registry.itemById(id); // 未知 id → NotFoundException → NOT_FOUND
        boolean applied = scopeStore.apply(taskId, ctx.ownerKey(), token);
        if (!applied) {
            ctx.err(Rpc.ERR_NOT_FOUND, "任务不存在或无权操作: " + taskId);
            return;
        }
        // 业务标记写入由注册方自己实现(try/catch 兜底,异常不阻塞 RPC 应答)
        try {
            item.selectHandler().onSelect(item, taskId);
        } catch (RuntimeException e) {
            log.warn("slash 任务 token 业务 onSelect 失败 id={} task={}", id, taskId, e);
        }
        ctx.ok(Json.obj()
                .put("applied", true)
                .put("taskId", taskId)
                .put("token", token));
    }

    // ---- slash.cancel ----

    /**
     * slash.cancel:用户取消 slash 胶囊(底部 ❌ 或内联 ✕)时触发。
     * 流程:
     * 1. resolveItem(id, token) 反查条目(仅用于业务 onCancel;解析不到为 null,绝不让 RPC 失败);
     * 2. 有 taskId 时先从任务 meta 的 slashTaskTokens 移除自身 token(scopeStore.remove
     *    内部落盘 + 广播 task.updated);任务不存在/非归属 → removed=false 降级应答,不抛错;
     * 3. 无 taskId(草稿取消/内联 ✕)→ 不碰 meta,removed=false;
     * 4. 条目非空才调业务 cancelHandler(onCancel),异常仅记日志,不阻塞应答;
     * 5. 应答 { removed }。缺失条目/非 opaque token 一律安全降级,不抛 500。
     */
    private void cancelItem(RpcContext ctx) {
        String id = ctx.optStrParam("id", null);
        String token = ctx.strParam("token");
        String taskId = ctx.optStrParam("taskId", null);
        SlashCommandItem item = resolveItem(id, token);

        boolean removed = false;
        if (taskId != null && !taskId.isEmpty()) {
            removed = scopeStore.remove(taskId, ctx.ownerKey(), token);
            if (!removed) {
                ctx.ok(Json.obj().put("removed", false));
                return;
            }
        }
        if (item != null) {
            try {
                item.cancelHandler().onCancel(item, token, taskId);
            } catch (RuntimeException e) {
                log.warn("slash cancel 业务回调失败 id={} task={}", id, taskId, e);
            }
        }
        ctx.ok(Json.obj().put("removed", removed));
    }

    /**
     * 反查条目(仅用于业务 onCancel,解析失败一律返回 null 安全降级)。
     * 优先级:
     * a. id 非空 → registry.itemById(id);NotFoundException 视为条目已不存在 → null;
     * b. token 是 opaque 且 payload.slashId 文本非空 → itemById(slashId)(找不到 → null);
     *    否则按 kind 在 registry.list() 里取最后一个 kind 相等者(找不到 → null);
     * c. token 非 opaque(p==null)→ null。
     * 注:条目本身不暴露 kind,kind 取自其 insertText 内嵌 opaque token(见 itemKind)。
     */
    private SlashCommandItem resolveItem(String id, String token) {
        if (id != null && !id.isEmpty()) {
            try {
                return registry.itemById(id);
            } catch (NotFoundException e) {
                return null;
            }
        }
        SlashTokenEncoder.ParsedToken p = SlashTokenEncoder.parseToken(token);
        if (p == null) {
            return null;
        }
        JsonNode slashIdNode = p.payload().path("slashId");
        if (slashIdNode.isTextual() && !slashIdNode.asText().isEmpty()) {
            try {
                return registry.itemById(slashIdNode.asText());
            } catch (NotFoundException e) {
                return null;
            }
        }
        SlashCommandItem last = null;
        for (SlashCommandItem item : registry.list()) {
            if (p.kind().equals(itemKind(item))) {
                last = item;
            }
        }
        return last;
    }

    /** 条目自身不暴露 kind;从其 insertText 内嵌 opaque token 解析,非 opaque/无法解析返回 null。 */
    private static String itemKind(SlashCommandItem item) {
        SlashTokenEncoder.ParsedToken p = SlashTokenEncoder.parseToken(item.insertText());
        return p == null ? null : p.kind();
    }

    // ---- mention.query ----

    private void mentionQuery(RpcContext ctx) throws IOException {
        Sandbox sb = new Sandbox(workspaces.resolve(ctx.strParam("workspace")));
        String path = ctx.optStrParam("path", ".");
        String query = ctx.optStrParam("query", "").trim();
        Path dir = sb.resolveExisting(path);
        if (!Files.isDirectory(dir)) {
            throw new NotFoundException("不是目录: " + path);
        }

        ArrayNode entries = Json.arr();
        if (query.isEmpty()) {
            // 浏览模式:列当前目录顶层(目录优先,再按名;与 fs.list 排序一致)。
            for (Path c : listChildren(dir)) {
                String rel = sb.display(c);
                if (shouldHide(rel)) {
                    continue;
                }
                entries.add(entry(rel, c));
            }
        } else {
            // 搜索模式:广度优先收集 + 匹配打分排序(分高优先,同分短路径优先)+ 截断 10。
            List<Hit> hits = new ArrayList<>();
            collect(dir, sb, query.toLowerCase(), hits, new int[]{MAX_SEARCH_NODES});
            Collator zh = Collator.getInstance(Locale.CHINA);
            hits.sort(Comparator.comparingInt((Hit h) -> -h.score())
                    .thenComparingInt(h -> h.entry().path("fullPath").asText().length())
                    .thenComparing(h -> h.entry().path("fullPath").asText(), zh));
            for (int i = 0; i < hits.size() && i < MENTION_LIMIT; i++) {
                entries.add(hits.get(i).entry());
            }
        }
        ctx.ok(Json.obj().set("entries", entries));
    }

    /** 单条结果:工作区相对 path + name + kind + 业务绝对 fullPath(前端形态)。 */
    private static ObjectNode entry(String rel, Path p) {
        ObjectNode o = Json.obj();
        o.put("path", rel);
        o.put("name", p.getFileName().toString());
        o.put("kind", Files.isDirectory(p) ? "directory" : "file");
        o.put("fullPath", "/" + rel);
        return o;
    }

    /** 搜索命中条目(匹配分 + 结果节点,排序用)。 */
    private record Hit(int score, ObjectNode entry) {
    }

    /**
     * 广度优先收集命中条目(文件 + 目录都参与匹配,与老项目前端 collectAllEntries 一致)。
     * 预算按层消耗:深度优先会让单个巨型子树(如 vendored 源码树)吃光预算,浅层文件
     * 反而搜不到;广度优先保证浅层先入候选,配合打分排序后浅层强命中不被深树淹没。
     */
    private static void collect(Path root, Sandbox sb, String lowerQuery,
            List<Hit> hits, int[] budget) throws IOException {
        Deque<Path> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty() && budget[0] > 0) {
            for (Path c : listChildren(pending.poll())) {
                if (budget[0] <= 0) {
                    return;
                }
                budget[0]--;
                String rel = sb.display(c);
                if (shouldHide(rel)) {
                    continue;
                }
                if (Files.isDirectory(c)) {
                    pending.add(c);
                }
                int score = scoreMatch(lowerQuery,
                        c.getFileName().toString().toLowerCase(),
                        ("/" + rel).toLowerCase());
                if (score >= 0) {
                    hits.add(new Hit(score, entry(rel, c)));
                }
            }
        }
    }

    /**
     * 匹配打分(越大越优,-1 不命中),四档从高到低:
     * 文件名连续命中 &gt; 路径连续命中 &gt; 文件名子序列 &gt; 路径子序列;
     * 档内再加成/扣分:文件名前缀 +300、词首(分隔符后)+150、短文件名微加成,
     * 路径连续按出现位置扣分,子序列按命中跨度扣分(越紧凑越优)。
     */
    private static int scoreMatch(String q, String name, String full) {
        int idx = name.indexOf(q);
        if (idx >= 0) {
            int s = 1000 - Math.min(name.length(), 200);
            if (idx == 0) {
                s += 300;
            } else if (isWordStart(name, idx)) {
                s += 150;
            }
            return s;
        }
        int pidx = full.indexOf(q);
        if (pidx >= 0) {
            return 500 - Math.min(pidx, 200);
        }
        int span = subsequenceSpan(q, name);
        if (span >= 0) {
            return 200 - Math.min(span, 100);
        }
        span = subsequenceSpan(q, full);
        return span >= 0 ? 50 - Math.min(span, 200) : -1;
    }

    /** idx 是否为词首(串首,或前一字符是 - _ . 空格 / 等分隔符)。 */
    private static boolean isWordStart(String s, int idx) {
        if (idx <= 0) {
            return true;
        }
        char prev = s.charAt(idx - 1);
        return prev == '-' || prev == '_' || prev == '.' || prev == ' ' || prev == '/';
    }

    /**
     * 子序列匹配:query 各字符按顺序出现在 target 中(可不连续)。
     * 返回命中跨度(末个命中下标 - 首个命中下标 + 1,紧凑度惩罚用),不命中返回 -1。
     */
    private static int subsequenceSpan(String query, String target) {
        if (query.isEmpty()) {
            return 0;
        }
        int qi = 0;
        int first = -1;
        int last = -1;
        for (int ti = 0; ti < target.length() && qi < query.length(); ti++) {
            if (target.charAt(ti) == query.charAt(qi)) {
                if (first < 0) {
                    first = ti;
                }
                last = ti;
                qi++;
            }
        }
        return qi == query.length() ? last - first + 1 : -1;
    }

    /** 列目录直接子项(目录优先,再按名;与 fs.list 一致)。 */
    private static List<Path> listChildren(Path dir) throws IOException {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            ds.forEach(children::add);
        }
        children.sort(Comparator
                .comparing((Path p) -> !Files.isDirectory(p))
                .thenComparing(p -> p.getFileName().toString()));
        return children;
    }

    /** 隐藏规则(对齐前端 shouldHideWorkspacePath):/plugins 前缀 + .git 段。 */
    private static boolean shouldHide(String rel) {
        if (rel == null || rel.isEmpty()) {
            return false;
        }
        if (rel.equals("plugins") || rel.startsWith("plugins/")) {
            return true;
        }
        for (String seg : rel.split("/")) {
            if (".git".equals(seg)) {
                return true;
            }
        }
        return false;
    }
}
