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
 * sys.* 只读方法:能力发现(sys.methods)与运行信息(sys.info)。
 * 多 hub:hubConnected = 至少一条连接在线(逐条状态见 connections)。
 */
@Component
public class SysMethods {

    public SysMethods(RpcDispatcher dispatcher, WorkerProperties props, HubPool pool,
            WorkspaceManager workspaces) {
        dispatcher.register(RpcMethods.SYS_INFO, ctx -> ctx.ok(info(props, pool, workspaces)));
    }

    private static ObjectNode info(WorkerProperties props, HubPool pool, WorkspaceManager workspaces) {
        WorkerLimits limits = new WorkerLimits(
                props.getLimits().getMaxConcurrentTasks(),
                props.getLimits().getMaxConcurrentSubs(),
                props.getLimits().getAskTimeoutMs(),
                props.getLimits().getSubWaitTimeoutMs());
        ObjectNode o = Json.obj();
        o.put("workerId", props.getWorkerId());
        o.put("hubConnected", pool.anyConnected());
        o.put("workspaceRoot", workspaces.defaultRoot().toString()); // 默认工作区(§5.9,多工作区见 workspaces.list)
        o.put("systemDir", workspaces.systemDir().toString());
        o.set("limits", Json.toJson(limits));
        return o;
    }
}
