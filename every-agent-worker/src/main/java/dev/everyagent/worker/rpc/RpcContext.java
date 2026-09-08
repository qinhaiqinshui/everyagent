package dev.everyagent.worker.rpc;

import dev.everyagent.contract.json.Json;
import dev.everyagent.contract.rpc.Rpc;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.proto.Channels;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次 RPC 的上下文:应答回请求来源连接的 evt 频道(多 hub 下前端只听自己 hub);
 * 同一 reqId 至多一次 ok/err(§13.3)。ownerKey() = 连接命名空间 K(连接层身份;任务域不再据此做归属校验)。
 */
public final class RpcContext {

    private final HubLink conn;
    private final String reqId;
    private final String method;
    private final JsonNode params;
    private final AtomicBoolean answered = new AtomicBoolean();

    public RpcContext(HubLink conn, String reqId, String method, JsonNode params) {
        this.conn = conn;
        this.reqId = reqId;
        this.method = method;
        this.params = params == null ? Json.obj() : params;
    }

    /** 请求来源连接。 */
    public HubLink conn() {
        return conn;
    }

    /** 请求方连接命名空间 K(连接层身份;任务域不再据此做归属校验)。 */
    public String ownerKey() {
        return conn.k();
    }

    public String reqId() {
        return reqId;
    }

    public String method() {
        return method;
    }

    public JsonNode params() {
        return params;
    }

    public void ok(JsonNode result) {
        if (answered.compareAndSet(false, true)) {
            conn.pub(Channels.workerEvt(conn.k(), conn.workerId()), "rpc.ok", null,
                    Rpc.wireOk(reqId, result), null);
        }
    }

    public void err(String code, String message) {
        if (answered.compareAndSet(false, true)) {
            conn.pub(Channels.workerEvt(conn.k(), conn.workerId()), "rpc.err", null,
                    Rpc.wireErr(reqId, code, message), null);
        }
    }

    public void data(List<JsonNode> batch, boolean hasMore) {
        if (answered.get()) {
            return; // ok/err 已终答,忽略迟到的 data
        }
        conn.pub(Channels.workerEvt(conn.k(), conn.workerId()), "rpc.data", null,
                Rpc.wireData(reqId, batch, hasMore), null);
    }

    public void progress(String message, Integer pct) {
        if (answered.get()) {
            return;
        }
        conn.pub(Channels.workerEvt(conn.k(), conn.workerId()), "rpc.progress", null,
                Rpc.wireProgress(reqId, message, pct), null);
    }

    // ---- 参数读取帮助 ----

    public String strParam(String name) {
        JsonNode n = params.path(name);
        if (n.isMissingNode() || n.isNull() || n.asString().isEmpty()) {
            throw new BadParamsException("缺少参数 " + name);
        }
        return n.asString();
    }

    public String optStrParam(String name, String def) {
        JsonNode n = params.path(name);
        return n.isMissingNode() || n.isNull() ? def : n.asString();
    }

    public long optLongParam(String name, long def) {
        JsonNode n = params.path(name);
        return n.isMissingNode() || n.isNull() ? def : n.asLong(def);
    }
}
