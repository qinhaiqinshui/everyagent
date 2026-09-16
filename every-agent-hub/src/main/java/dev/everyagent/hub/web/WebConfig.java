package dev.everyagent.hub.web;

import dev.everyagent.contract.ids.Ids;
import dev.everyagent.hub.config.HubProperties;
import dev.everyagent.hub.ws.HubWsHandler;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.netty.http.server.WebsocketServerSpec;

import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;
import static org.springframework.web.reactive.function.server.ServerResponse.status;

@Configuration
public class WebConfig {

    private final HubProperties props;
    private final ConfigurableApplicationContext applicationContext;

    public WebConfig(HubProperties props, ConfigurableApplicationContext applicationContext) {
        this.props = props;
        this.applicationContext = applicationContext;
    }

    @Bean
    public SimpleUrlHandlerMapping wsMapping(HubWsHandler handler) {
        return new SimpleUrlHandlerMapping(Map.of("/ws", handler), -1);
    }

    /**
     * /ws 升级策略:Reactor Netty 默认单帧上限仅 64 KiB,超限帧被传输层直接拒收断连
     * (task.sync 大事件回放帧曾因此丢失)。按 hub.max-frame-bytes 对齐并加 64 KiB 余量,
     * 让超限帧优先命中应用层 HubConnection 的 E_FRAME_TOO_LARGE 温和错误(连接保活)。
     * Framework 7 起策略无 setMaxFramePayloadLength setter,经 WebsocketServerSpec 供应商配置。
     * 本 bean 替换 Boot 自动配置的同名默认 adapter。
     */
    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter(HubProperties props) {
        int limit = (int) Math.min(Integer.MAX_VALUE, (long) props.getMaxFrameBytes() + 65_536L);
        ReactorNettyRequestUpgradeStrategy strategy = new ReactorNettyRequestUpgradeStrategy(
                () -> WebsocketServerSpec.builder().maxFramePayloadLength(limit));
        return new WebSocketHandlerAdapter(new HandshakeWebSocketService(strategy));
    }

    @Bean
    public RouterFunction<ServerResponse> health() {
        return route(GET("/health"), req -> ok().bodyValue(Map.of("status", "UP")));
    }

    /**
     * 管理端点:认证后返回 hub 身份标识。
     * 认证方式:X-Admin-Key 请求头,经 Ids.ownerKey() 计算 sha256 与 hub.hub-key-sha 比对。
     */
    @Bean
    public RouterFunction<ServerResponse> adminIdentify() {
        return route(GET("/admin/identify"),
                req -> authenticate(req.headers().firstHeader("X-Admin-Key"))
                        ? ok().bodyValue(Map.of("service", "hub"))
                        : status(401).bodyValue(Map.of("error", "unauthorized")));
    }

    /**
     * 管理端点:认证后延迟 500ms 触发 ApplicationContext.close(),延迟在独立线程执行
     * 以确保 HTTP 响应能正常返回。
     */
    @Bean
    public RouterFunction<ServerResponse> adminShutdown() {
        return route(POST("/admin/shutdown"),
                req -> {
                    if (!authenticate(req.headers().firstHeader("X-Admin-Key"))) {
                        return status(401).bodyValue(Map.of("error", "unauthorized"));
                    }
                    Thread t = new Thread(() -> {
                        try {
                            Thread.sleep(500L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        applicationContext.close();
                    }, "hub-shutdown");
                    t.setDaemon(false);
                    t.start();
                    return ok().bodyValue(Map.of("status", "shutting down"));
                });
    }

    /**
     * 管理端点认证:将 X-Admin-Key 请求头值 trim 后经 Ids.ownerKey() 计算 sha256,
     * 与 hub 启动时派生的 hub-key-sha 比对。空值直接拒绝。
     */
    private boolean authenticate(String adminKey) {
        if (adminKey == null || adminKey.isBlank()) {
            return false;
        }
        return Ids.ownerKey(adminKey.trim()).equals(props.getHubKeySha());
    }
}
