package dev.everyagent.plugin.subagent;

import dev.everyagent.contract.json.Json;
import dev.everyagent.contract.rpc.Rpc;
import dev.everyagent.plugin.api.rpc.RpcContext;
import dev.everyagent.worker.task.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * task.agents RPC 处理器（从 TaskManager.rpcTaskAgents 迁入插件域）。
 *
 * <p>唯一取数口：live 任务读 SubAgentLedger 内存台账，
 * 冷任务读磁盘 agents.json（回退 meta.agents）。
 */
public class SubAgentRpcHandler {

    private static final Logger log = LoggerFactory.getLogger(SubAgentRpcHandler.class);

    private final SubAgentLedger ledger;
    private final TaskStore store;

    public SubAgentRpcHandler(SubAgentLedger ledger, TaskStore store) {
        this.ledger = ledger;
        this.store = store;
    }

    /**
     * task.agents RPC 处理方法（经 RpcDispatcher 调用）。
     */
    public void handleTaskAgents(RpcContext ctx) {
        String taskId = ctx.strParam("taskId");

        // 判断任务是否存在
        boolean known = store.taskDirExists(taskId);
        if (!known) {
            ctx.err(Rpc.ERR_NOT_FOUND, "task 不存在: " + taskId);
            return;
        }

        List<ObjectNode> agents = new ArrayList<>();
        String mainAgentId = "";

        // 优先读 live 内存台账
        List<ObjectNode> live = ledger.getLiveAgents(taskId);
        if (live != null) {
            // live 任务：从台账读
            for (ObjectNode a : live) {
                agents.add(a.deepCopy());
            }
            // mainAgentId 从 store.readMeta 获取
            Path dir = store.dirOf(taskId);
            ObjectNode meta = store.readMeta(dir);
            if (meta != null) {
                mainAgentId = meta.path("mainAgentId").asString("");
            }
        } else {
            // 冷任务：从磁盘读
            Path dir = store.dirOf(taskId);
            ObjectNode meta = store.readMeta(dir);
            mainAgentId = meta == null ? "" : meta.path("mainAgentId").asString("");

            List<ObjectNode> disk = store.readAgents(dir);
            if (disk != null) {
                for (ObjectNode a : disk) {
                    agents.add(a.deepCopy());
                }
            } else if (meta != null) {
                // 旧任务兼容：从 meta.agents 回退
                JsonNode legacyAgents = meta.path("agents");
                if (legacyAgents.isArray()) {
                    for (JsonNode a : legacyAgents) {
                        if (a.isObject()) {
                            agents.add(((ObjectNode) a).deepCopy());
                        }
                    }
                }
            }
        }

        // 组装应答
        ObjectNode r = Json.obj();
        ArrayNode arr = Json.arr();
        // 排序
        agents.sort(java.util.Comparator.comparingLong(a -> a.path("createdAt").asLong(0)));
        agents.forEach(arr::add);
        r.set("agents", arr);
        r.put("mainAgentId", mainAgentId);
        ctx.ok(r);
    }
}
