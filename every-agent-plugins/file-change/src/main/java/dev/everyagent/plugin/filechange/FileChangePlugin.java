package dev.everyagent.plugin.filechange;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

public class FileChangePlugin implements EveryAgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(FileChangePlugin.class);

    /** roundId 形态白名单(ShortIds:小写字母/数字/下划线/连字符),兼作 RPC 入参防路径穿越。 */
    private static final Pattern ROUND_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private WorkerServices services;

    @Override
    public String id() { return "file-change"; }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        this.services = ctx.services();
        FileChangeAdvisorProvider provider = new FileChangeAdvisorProvider(services.task());
        services.addRoundClosedListener(provider);
        ctx.registerAdvisorProvider(provider);
        ctx.registerRpcMethod("task.fileChanges", this::rpcTaskFileChanges);
        log.info("[file-change] 已注册 FileChangeAdvisorProvider + RoundClosedListener + task.fileChanges RPC");
    }

    /**
     * {@code task.fileChanges}(插件自注册,task 核心不感知,架构 §7.15.2):
     * <ul>
     *   <li>带 {@code roundId} → 该轮变更<b>全文</b> {@code {changes:[...]}}(含 beforeContent/afterContent);</li>
     *   <li>省略 {@code roundId} → 全任务各轮<b>轻量摘要</b>
     *       {@code {rounds:[{roundId, changes:[{filePath,fileName,changeType,saveCount}]}]}}——
     *       前端轮末面板一次拉全,避免逐轮 N 次 RPC;摘要由 {@code file-changes/*.json} 反推,
     *       不落 rounds.jsonl 行,旧任务同样可显示。</li>
     * </ul>
     */
    private void rpcTaskFileChanges(RpcContext ctx) {
        String taskId = ctx.strParam("taskId");
        String roundId = ctx.optStrParam("roundId", null);
        try {
            Path dataDir = services.dataDirOf(taskId);
            if (dataDir == null) {
                ctx.err("NOT_FOUND", "task 不存在: " + taskId);
                return;
            }
            Path dir = dataDir.resolve("file-changes");
            if (roundId == null || roundId.isBlank()) {
                ctx.ok(Json.obj().set("rounds", allRoundsLightSummary(taskId, dir)));
                return;
            }
            if (!ROUND_ID_PATTERN.asMatchPredicate().test(roundId)) {
                ctx.err("BAD_PARAMS", "roundId 形态非法: " + roundId);
                return;
            }
            Path f = dir.resolve(roundId + ".json");
            if (!Files.isRegularFile(f)) {
                log.debug("[file-change] task.fileChanges 该轮无分片 task={} round={} file={}", taskId, roundId, f);
                ctx.ok(Json.obj().set("changes", Json.arr()));
                return;
            }
            var full = Json.parse(Files.readString(f, java.nio.charset.StandardCharsets.UTF_8));
            JsonNode changes = full.path("changes");
            log.debug("[file-change] task.fileChanges 单轮全文 task={} round={} 文件数={}",
                    taskId, roundId, changes.isArray() ? changes.size() : 0);
            ctx.ok(Json.obj().set("changes", changes));
        } catch (Exception e) {
            log.warn("[file-change] task.fileChanges RPC 读取失败 task={} round={}", taskId, roundId, e);
            ctx.err("INTERNAL", "读取文件变更失败");
        }
    }

    /** 全任务各轮轻量摘要:扫 {@code file-changes/*.json},逐文件剥掉 before/after 全文。 */
    private ArrayNode allRoundsLightSummary(String taskId, Path dir) throws Exception {
        ArrayNode rounds = Json.arr();
        if (!Files.isDirectory(dir)) {
            log.debug("[file-change] task.fileChanges 全量摘要:目录不存在 task={} dir={}", taskId, dir);
            return rounds;
        }
        try (var files = Files.list(dir)) {
            List<Path> shards = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
            for (Path shard : shards) {
                String rid = shard.getFileName().toString();
                rid = rid.substring(0, rid.length() - ".json".length());
                ArrayNode light;
                try {
                    var full = Json.parse(Files.readString(shard, java.nio.charset.StandardCharsets.UTF_8));
                    light = toLightSummary(full.path("changes"));
                } catch (Exception e) { // 单个分片损坏不影响其他轮
                    log.warn("[file-change] 分片解析失败,跳过 task={} file={}", taskId, shard, e);
                    continue;
                }
                if (light.isEmpty()) {
                    continue;
                }
                rounds.add(Json.obj().put("roundId", rid).set("changes", light));
            }
        }
        log.debug("[file-change] task.fileChanges 全量摘要 task={} 有变更轮数={}", taskId, rounds.size());
        return rounds;
    }

    /** 变更数组 → 轻量摘要数组(剥掉 beforeContent/afterContent,只留列表渲染所需的四个字段)。 */
    private static ArrayNode toLightSummary(JsonNode changes) {
        ArrayNode light = Json.arr();
        if (!changes.isArray()) {
            return light;
        }
        for (JsonNode c : changes) {
            light.addObject()
                    .put("filePath", c.path("filePath").asString(""))
                    .put("fileName", c.path("fileName").asString(""))
                    .put("changeType", c.path("changeType").asString("updated"))
                    .put("saveCount", c.path("saveCount").asInt(1));
        }
        return light;
    }
}
