package dev.everyagent.contract.frame;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.everyagent.contract.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * 控制帧(架构 §3.1)。接收侧 must-ignore:未知字段一律忽略。
 */
public final class Frames {

    // type
    public static final String HELLO = "hello";
    public static final String WELCOME = "welcome";
    public static final String SUB = "sub";
    public static final String UNSUB = "unsub";
    public static final String PUB = "pub";
    public static final String MSG = "msg";
    public static final String ERROR = "error";

    // error codes(hub 连接级,架构 §13.4)
    public static final String E_NOT_AUTHENTICATED = "NOT_AUTHENTICATED";
    public static final String E_VERSION_MISMATCH = "VERSION_MISMATCH";
    public static final String E_ACL_DENIED = "ACL_DENIED";
    public static final String E_FRAME_TOO_LARGE = "FRAME_TOO_LARGE";
    public static final String E_RATE_LIMITED = "RATE_LIMITED";

    // hub 生成的 stream 频道订阅通知事件(架构演进:hub 感知前端在 stream 频道上的 sub/unsub,
    // 以 msg 帧向该 owner 下全部在线 worker 投递;payload={sessionId, taskId})。
    public static final String SUBSCRIBER_JOIN = "subscriber.join";
    public static final String SUBSCRIBER_LEAVE = "subscriber.leave";

    /** v2:输入改走 worker 级频道(u.K.worker.<id>.input,弃 per-task input)+ 存储按用户隔离。 */
    public static final int PROTOCOL_VERSION = 2;

    private Frames() {
    }

    // ---- 帧 records(用于解析;构造发送帧用下面的 wire* 帮助方法)----

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hello(Integer ver, String role, String apiKey, String clientId, String hubKey, Map<String, Object> meta) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Welcome(Integer ver, String sessionId, long serverTs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sub(String channel) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Unsub(String channel) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Pub(String mid, String channel, String event, Long seq, long ts, JsonNode payload, JsonNode ext) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record From(String clientId, String role) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Msg(String channel, String event, Long seq, long ts, From from, JsonNode payload, JsonNode ext) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorFrame(String code, String detail) {
    }

    // ---- 发送帧构造 ----

    public static String wireHello(String role, String apiKey, String clientId, Map<String, Object> meta) {
        return wireHello(role, apiKey, clientId, null, meta);
    }

    /** hello 携带可选 hubKey(对 hub 的保护);hubKey 为空时不上线字段(向后兼容)。 */
    public static String wireHello(String role, String apiKey, String clientId, String hubKey, Map<String, Object> meta) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("type", HELLO);
        o.put("ver", PROTOCOL_VERSION);
        o.put("role", role);
        o.put("apiKey", apiKey);
        o.put("clientId", clientId);
        if (hubKey != null && !hubKey.isBlank()) {
            o.put("hubKey", hubKey);
        }
        if (meta != null && !meta.isEmpty()) {
            o.set("meta", Json.toJson(meta));
        }
        return Json.write(o);
    }

    public static String wireSub(String channel) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("type", SUB);
        o.put("channel", channel);
        return Json.write(o);
    }

    public static String wireUnsub(String channel) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("type", UNSUB);
        o.put("channel", channel);
        return Json.write(o);
    }

    /**
     * 构造 pub 帧。seq 仅 stream 频道事件携带,其余频道传 null。
     *
     * <p>seq 在 wire 上<b>一律以字符串传输</b>({@code "17592…"}):seq 是 64 位 Snowflake
     * long(≈ 10^17 量级),远超 JS {@code Number.MAX_SAFE_INTEGER}(2^53 ≈ 9.007e15)。
     * 若按 JSON number 输出,浏览器端 {@code JSON.parse} 会丢精度——同毫秒相邻事件(如
     * {@code message} 与其 {@code tool.result})会被解析成同一个 double,前端按 seq 去重时
     * 把后到的事件误判为旧轮/重复而丢弃(工具结果即因此不可见)。字符串化后 hub 原样透传,
     * 前端 {@code compareSeq} 按位数优先的字符串序比较,等价数值序且不丢精度。
     */
    public static String wirePub(String mid, String channel, String event, Long seq, long ts, JsonNode payload, JsonNode ext) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("type", PUB);
        if (mid != null) {
            o.put("mid", mid);
        }
        o.put("channel", channel);
        o.put("event", event);
        if (seq != null) {
            o.put("seq", seq.toString());
        }
        o.put("ts", ts);
        o.set("payload", payload == null ? Json.MAPPER.createObjectNode() : payload);
        if (ext != null && !ext.isNull()) {
            o.set("ext", ext);
        }
        return Json.write(o);
    }
}
