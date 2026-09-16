package dev.everyagent.worker.web;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/**
 * 管理端点(仅监听 127.0.0.1,与 /health 同端口):
 * <ul>
 *   <li>{@code GET /admin/identify} — 返回 worker 身份信息;</li>
 *   <li>{@code POST /admin/shutdown} — 异步延迟关闭 ApplicationContext。</li>
 * </ul>
 * 认证:请求头 {@code X-Admin-Key} 与 worker.hubs[0].apiKey 明文比对。
 */
@RestController
public class ShutdownController {

    private final WorkerProperties props;
    private final ConfigurableApplicationContext context;

    public ShutdownController(WorkerProperties props, ConfigurableApplicationContext context) {
        this.props = props;
        this.context = context;
    }

    /**
     * 校验 X-Admin-Key 与 worker.hubs[0].apiKey 匹配。
     * 返回 null 表示认证通过;否则返回对应错误响应。
     */
    private ResponseEntity<ObjectNode> authenticate(String adminKey) {
        if (props.getHubs() == null || props.getHubs().isEmpty()) {
            ObjectNode err = Json.obj();
            err.put("error", "no hubs configured");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
        String expected = props.getHubs().get(0).getApiKey();
        if (expected == null || expected.isBlank()) {
            ObjectNode err = Json.obj();
            err.put("error", "apiKey not configured");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
        if (adminKey == null || !expected.equals(adminKey)) {
            ObjectNode err = Json.obj();
            err.put("error", "unauthorized");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err);
        }
        return null;
    }

    /** 返回 worker 身份信息。 */
    @GetMapping("/admin/identify")
    public ResponseEntity<ObjectNode> identify(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey) {
        ResponseEntity<ObjectNode> err = authenticate(adminKey);
        if (err != null) {
            return err;
        }
        ObjectNode o = Json.obj();
        o.put("service", "worker");
        o.put("workerId", props.getWorkerId());
        return ResponseEntity.ok(o);
    }

    /** 异步延迟 500ms 关闭 ApplicationContext,确保 HTTP 响应先行返回。 */
    @PostMapping("/admin/shutdown")
    public ResponseEntity<ObjectNode> shutdown(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey) {
        ResponseEntity<ObjectNode> err = authenticate(adminKey);
        if (err != null) {
            return err;
        }
        ObjectNode o = Json.obj();
        o.put("status", "shutting down");
        // 在独立线程中延迟关闭,保证当前 HTTP 响应能够正常返回
        Thread shutdownThread = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            context.close();
        });
        shutdownThread.setDaemon(false);
        shutdownThread.setName("admin-shutdown");
        shutdownThread.start();
        return ResponseEntity.ok(o);
    }
}
