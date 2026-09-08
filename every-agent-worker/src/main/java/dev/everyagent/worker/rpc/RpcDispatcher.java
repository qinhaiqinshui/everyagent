package dev.everyagent.worker.rpc;

import dev.everyagent.contract.json.Json;
import dev.everyagent.contract.rpc.Rpc;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.proto.Channels;
import dev.everyagent.worker.proto.RpcMethods;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * RPC 分发器(架构 §5.5):每条 hub 连接各订阅自己的 cmd 频道(u.K.worker.id.cmd),
 * 收到 rpc 事件后按方法表分发;应答回请求来源连接。每个请求跑在独立虚拟线程上,
 * rpc.cancel 通过 Future.cancel(true) 打断。
 */
@Component
public class RpcDispatcher implements HubPool.Listener {

    public interface Method {
        void handle(RpcContext ctx) throws Exception;
    }

    private static final Logger log = LoggerFactory.getLogger(RpcDispatcher.class);

    private final HubPool pool;
    private final WorkerProperties props;
    private final Map<String, Method> methods = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> inFlight = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService vt = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    public RpcDispatcher(HubPool pool, WorkerProperties props) {
        this.pool = pool;
        this.props = props;
    }

    @PostConstruct
    void init() {
        pool.addListener(this);
        // cmd 频道常订阅:每条连接各自的命名空间(连接建立/重连时由 HubLink 重放)
        for (HubLink conn : pool.conns()) {
            conn.sub(Channels.workerCmd(conn.k(), props.getWorkerId()));
        }
        register(RpcMethods.SYS_METHODS, ctx -> ctx.ok(Json.toJson(methods.keySet())));
    }

    public void register(String method, Method handler) {
        methods.put(method, handler);
    }

    public static String cmdChannel(HubLink conn) {
        return Channels.workerCmd(conn.k(), conn.workerId());
    }

    @Override
    public void onHubMessage(HubLink conn, JsonNode frame) {
        String channel = frame.path("channel").asString("");
        String event = frame.path("event").asString("");
        if (!channel.equals(cmdChannel(conn)) || !"rpc".equals(event)) {
            return;
        }
        JsonNode payload = frame.path("payload");
        String reqId = payload.path("reqId").asString("");
        String method = payload.path("method").asString("");
        if (reqId.isEmpty() || method.isEmpty()) {
            log.warn("rpc 请求缺 reqId/method: {}", payload);
            return;
        }
        if (Rpc.RPC_CANCEL.equals(method)) {
            handleCancel(conn, reqId, payload.path("params"));
            return;
        }
        Method handler = methods.get(method);
        if (handler == null) {
            replyDirect(conn, reqId, Rpc.ERR_UNKNOWN_METHOD, "未知方法: " + method);
            return;
        }
        RpcContext ctx = new RpcContext(conn, reqId, method, payload.path("params"));
        Future<?> future = vt.submit(() -> {
            try {
                handler.handle(ctx);
            } catch (BadParamsException e) {
                ctx.err(Rpc.ERR_BAD_PARAMS, e.getMessage());
            } catch (NotFoundException e) {
                ctx.err(Rpc.ERR_NOT_FOUND, e.getMessage());
            } catch (SandboxViolationException e) {
                ctx.err(Rpc.ERR_SANDBOX_DENIED, e.getMessage());
            } catch (AuthRequiredException e) {
                ctx.err(Rpc.ERR_AUTH_REQUIRED, e.getMessage());
            } catch (java.util.concurrent.CancellationException e) {
                // 被取消:不再应答
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ctx.err(Rpc.ERR_INTERNAL, "请求被取消");
            } catch (Throwable t) {
                log.error("rpc {} 执行异常", method, t);
                ctx.err(Rpc.ERR_INTERNAL, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            } finally {
                inFlight.remove(reqId);
            }
        });
        inFlight.put(reqId, future);
    }

    private void handleCancel(HubLink conn, String reqId, JsonNode params) {
        String target = params.path("reqId").asString("");
        RpcContext ctx = new RpcContext(conn, reqId, Rpc.RPC_CANCEL, params);
        if (target.isEmpty()) {
            ctx.err(Rpc.ERR_BAD_PARAMS, "缺少参数 reqId");
            return;
        }
        Future<?> f = inFlight.get(target);
        if (f == null) {
            ctx.err(Rpc.ERR_NOT_FOUND, "请求不存在或已完成: " + target);
            return;
        }
        f.cancel(true);
        ctx.ok(Json.obj().put("cancelled", true).put("reqId", target));
    }

    private void replyDirect(HubLink conn, String reqId, String code, String message) {
        conn.pub(Channels.workerEvt(conn.k(), conn.workerId()), "rpc.err", null,
                Rpc.wireErr(reqId, code, message), null);
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        vt.shutdownNow();
    }
}
