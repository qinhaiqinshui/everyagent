package dev.everyagent.plugin.subagent;

import dev.everyagent.contract.json.Json;
import dev.everyagent.contract.rpc.Rpc;
import dev.everyagent.plugin.api.rpc.RpcContext;
import dev.everyagent.plugin.api.task.TaskStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * task.agents RPC 处理器（从 TaskManager.rpcTaskAgents 迁入插件域;§8.2 壳/核分层）。
 *
 * <p>唯一取数口：live 任务读 SubAgentLedger 内存台账，
 * 冷任务读磁盘 agents.json（回退 meta.agents）。
 *
 * <p>壳/核分层：冷路径 readMeta(dir) 取 mainAgentId / dirOf(taskId) 继续用
 * TaskStoreService（meta.json 是任务摘要,mainAgentId 是任务概念——壳留 task 面）；
 * 核心列表逻辑（读 agents.json）复用中性台账 reader（{@link SubAgentLedger#readAgents}）。
 */
public class SubAgentRpcHandler {

    private static final Logger log = LoggerFactory.getLogger(SubAgentRpcHandler.class);

    /** RPC 方法名（worker RpcMethods 已清退,常量跟注册方走,§8.5④）。 */
    public static final String TASK_AGENTS = "task.agents";

    private final SubAgentLedger ledger;
    private final TaskStoreService store;

    public SubAgentRpcHandler(SubAgentLedger ledger, TaskStoreService store) {
        this.ledger = ledger;
        this.store = store;
    }

    /**
     * task.agents RPC 处理方法（经 RpcDispatcher 调用,方法名常量 {@link #TASK_AGENTS}）。
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

            List<ObjectNode> disk = ledger.readAgents(dir);
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
