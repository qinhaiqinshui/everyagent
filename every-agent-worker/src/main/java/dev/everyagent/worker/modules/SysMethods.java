package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.proto.ConfigDtos.WorkerLimits;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.RpcDispatcher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/**
 * sys.* 只读方法:能力发现(sys.methods)、运行信息(sys.info)与 hub 连接清单(sys.hubs)。
 * 多 hub:hubConnected = 至少一条连接在线(逐条状态见 sys.hubs)。
 */
@Component
public class SysMethods {

    public SysMethods(RpcDispatcher dispatcher, WorkerProperties props, HubPool pool,
            WorkspaceManager workspaces) {
        dispatcher.register(RpcMethods.SYS_INFO, ctx -> ctx.ok(info(props, pool, workspaces)));
        dispatcher.register(RpcMethods.SYS_HUBS, ctx -> ctx.ok(hubs(pool)));
    }

    /**
     * 本 worker 的 hub 连接清单(只读):{hubs:[{url, connected, local}]}。
     * 只含地址与状态 —— 凭据(apiKey/hubKey)明文不出 worker(同 config.get 的掩码原则)。
     */
    private static ObjectNode hubs(HubPool pool) {
        ObjectNode o = Json.obj();
        o.set("hubs", Json.toJson(pool.hubViews()));
        return o;
    }

    private static ObjectNode info(WorkerProperties props, HubPool pool, WorkspaceManager workspaces) {
        WorkerLimits limits = new WorkerLimits(
                props.getLimits().getMaxConcurrentTasks(),
                props.getLimits().getAskTimeoutMs());
        ObjectNode o = Json.obj();
        o.put("workerId", props.getWorkerId());
        o.put("hubConnected", pool.anyConnected());
        o.put("workspaceRoot", workspaces.defaultRoot().toString()); // 默认工作区(§5.9,多工作区见 workspaces.list)
        o.put("systemDir", workspaces.systemDir().toString());
        o.set("limits", Json.toJson(limits));
        return o;
    }
}
