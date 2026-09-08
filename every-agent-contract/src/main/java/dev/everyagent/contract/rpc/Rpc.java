package dev.everyagent.contract.rpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.everyagent.contract.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * RPC 信封与应答(架构 §3.5)。rpc 帧走 cmd 频道,应答统一广播到 evt 频道,reqId 供匹配。
 * contract 只定协议:方法名属于具体业务,归各端(如 worker 的 RpcMethods)。
 */
public final class Rpc {

    // rpc.err 错误码(信封级,架构 §13.4)
    public static final String ERR_UNKNOWN_METHOD = "UNKNOWN_METHOD";
    public static final String ERR_BAD_PARAMS = "BAD_PARAMS";
    public static final String ERR_NOT_FOUND = "NOT_FOUND";
    public static final String ERR_SANDBOX_DENIED = "SANDBOX_DENIED";
    public static final String ERR_BUSY = "BUSY";
    public static final String ERR_INTERNAL = "INTERNAL";
    /** 业务方需要用户补充凭证(如 git.clone 远端认证):由调用方弹窗收集后重试。 */
    public static final String ERR_AUTH_REQUIRED = "AUTH_REQUIRED";

    /** RPC 取消的方法名——信封协议自身的一部分,随信封定义。 */
    public static final String RPC_CANCEL = "rpc.cancel";

    private Rpc() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RpcRequest(String reqId, String method, JsonNode params) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RpcOk(String reqId, JsonNode result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RpcErr(String reqId, String code, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RpcData(String reqId, List<JsonNode> batch, boolean hasMore) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RpcProgress(String reqId, String message, Integer pct) {
    }

    // ---- wire 构造(evt 频道上的事件 payload)----

    public static JsonNode wireOk(String reqId, JsonNode result) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("reqId", reqId);
        o.set("result", result == null ? Json.MAPPER.createObjectNode() : result);
        return o;
    }

    public static JsonNode wireErr(String reqId, String code, String message) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("reqId", reqId);
        o.put("code", code);
        o.put("message", message == null ? "" : message);
        return o;
    }

    public static JsonNode wireData(String reqId, List<JsonNode> batch, boolean hasMore) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("reqId", reqId);
        o.set("batch", Json.MAPPER.createArrayNode().addAll(batch));
        o.put("hasMore", hasMore);
        return o;
    }

    public static JsonNode wireProgress(String reqId, String message, Integer pct) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("reqId", reqId);
        o.put("message", message);
        if (pct != null) {
            o.put("pct", pct);
        }
        return o;
    }
}
