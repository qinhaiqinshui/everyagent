package dev.everyagent.plugin.filechange;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public class FileChangePlugin implements EveryAgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(FileChangePlugin.class);

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

    private void rpcTaskFileChanges(RpcContext ctx) {
        String taskId = ctx.strParam("taskId");
        String roundId = ctx.strParam("roundId");
        if (taskId == null || taskId.isEmpty() || roundId == null || roundId.isEmpty()) {
            ctx.err("BAD_PARAMS", "taskId 和 roundId 必填");
            return;
        }
        try {
            Path dataDir = services.dataDirOf(taskId);
            if (dataDir == null) {
                ctx.err("NOT_FOUND", "task 不存在: " + taskId);
                return;
            }
            Path f = dataDir.resolve("file-changes").resolve(roundId + ".json");
            if (!Files.isRegularFile(f)) {
                ctx.ok(Json.obj().set("changes", Json.arr()));
                return;
            }
            var full = Json.parse(Files.readString(f, java.nio.charset.StandardCharsets.UTF_8));
            ctx.ok(Json.obj().set("changes", full.path("changes")));
        } catch (Exception e) {
            log.warn("[file-change] task.fileChanges RPC 读取失败 task={} round={}", taskId, roundId, e);
            ctx.err("INTERNAL", "读取文件变更失败");
        }
    }
}
