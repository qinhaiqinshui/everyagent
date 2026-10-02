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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * task.agents RPC 处理器（从 TaskManager.rpcTaskAgents 迁入插件域;§8.2 壳/核分层）。
 *
 * <p>唯一取数口：读磁盘 agents.json（worker AgentLedger 落盘），回退 meta.agents。
 *
 * <p>壳/核分层：冷路径 readMeta(dir) 取 mainAgentId / dirOf(taskId) 继续用
 * TaskStoreService（meta.json 是任务摘要,mainAgentId 是任务概念——壳留 task 面）；
 * 核心列表逻辑（读 agents.json）直接内联 JSON 解析。
 */
public class SubAgentRpcHandler {

    private static final Logger log = LoggerFactory.getLogger(SubAgentRpcHandler.class);

    /** RPC 方法名（worker RpcMethods 已清退,常量跟注册方走,§8.5④）。 */
    public static final String TASK_AGENTS = "task.agents";

    private final TaskStoreService store;

    public SubAgentRpcHandler(TaskStoreService store) {
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

        // 读磁盘 agents.json（worker AgentLedger 落盘）
        Path dir = store.dirOf(taskId);
        ObjectNode meta = store.readMeta(dir);
        mainAgentId = meta == null ? "" : meta.path("mainAgentId").asString("");

        List<ObjectNode> disk = readAgentsJson(dir);
        if (disk != null) {
            for (ObjectNode a : disk) {
                // 按 creator 过滤：只保留 subagent 创建的 agent
                JsonNode aMeta = a.path("metadata");
                if (aMeta.isObject()) {
                    String creator = aMeta.path("creator").asString("");
                    if (!creator.isEmpty() && !"subagent".equals(creator)) {
                        continue; // 非 subagent 创建的 agent，跳过
                    }
                }
                // 无 metadata 或 creator 为空 = 旧格式 subagent 创建的，保留
                agents.add(a.deepCopy());
            }
        } else if (meta != null) {
            // 旧任务兼容：从 meta.agents 回退（旧格式无 metadata，全部保留）
            JsonNode legacyAgents = meta.path("agents");
            if (legacyAgents.isArray()) {
                for (JsonNode a : legacyAgents) {
                    if (a.isObject()) {
                        // 按 creator 过滤：只保留 subagent 创建的 agent
                        JsonNode aMeta = a.path("metadata");
                        if (aMeta.isObject()) {
                            String creator = aMeta.path("creator").asString("");
                            if (!creator.isEmpty() && !"subagent".equals(creator)) {
                                continue;
                            }
                        }
                        agents.add(((ObjectNode) a).deepCopy());
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

    /**
     * 读任务数据目录下 agents.json（形状 {@code {"agents":[...]}}）。
     * 文件不存在/损坏/形状不符返回 null（null = 调用方回退旧格式 meta.agents）。
     */
    private List<ObjectNode> readAgentsJson(Path dir) {
        Path f = dir.resolve("agents.json");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            JsonNode agents = Json.parse(Files.readString(f)).path("agents");
            if (!agents.isArray()) {
                log.debug("agents.json 形状异常(无 agents 数组): {}", f);
                return null;
            }
            List<ObjectNode> out = new ArrayList<>();
            for (JsonNode a : agents) {
                if (a.isObject()) {
                    out.add((ObjectNode) a);
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            log.debug("agents.json 读取失败 {}", f, e);
            return null;
        }
    }
}
