package dev.everyagent.hub.ws;

import dev.everyagent.hub.config.HubProperties;
import dev.everyagent.hub.presence.PresenceService;
import dev.everyagent.hub.reg.ChannelRegistry;
import dev.everyagent.hub.reg.ConnectionRegistry;
import dev.everyagent.hub.util.HelloRateLimiter;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * /ws 入口:每连接一个 HubConnection;入站按序处理,出站走有界 sink(慢消费者保护)。
 */
@Component
public class HubWsHandler implements org.springframework.web.reactive.socket.WebSocketHandler {

    private final HubProperties props;
    private final ConnectionRegistry connections;
    private final ChannelRegistry channels;
    private final PresenceService presence;
    private final HelloRateLimiter helloLimiter;
    private final ScheduledExecutorService pingScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "hub-ping");
                t.setDaemon(true);
                return t;
            });

    public HubWsHandler(HubProperties props, ConnectionRegistry connections, ChannelRegistry channels,
                        PresenceService presence) {
        this.props = props;
        this.connections = connections;
        this.channels = channels;
        this.presence = presence;
        this.helloLimiter = new HelloRateLimiter(props.getHelloRatePerMinute());
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        HubConnection conn = new HubConnection(session, props, connections, channels, presence, helloLimiter);
        conn.startPings(pingScheduler);

        Mono<Void> inbound = session.receive()
                .doOnNext(conn::onFrame)
                .then()
                .doFinally(sig -> conn.transportGone());

        Mono<Void> outbound = session.send(conn.outboundFlux());

        return Mono.when(inbound, outbound).doFinally(sig -> conn.cleanup());
    }

    @PreDestroy
    void shutdown() {
        pingScheduler.shutdownNow();
        try {
            pingScheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
