package dev.everyagent.worker.hub;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.proto.Channels;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 多 hub 注册池:worker 可同时挂到 N 个 hub(每 {url, apiKey, hubKey} 一条独立连接/重连循环)。
 *
 * <p>输出路由(hub 不感知业务,路由在 worker 侧):
 * <ul>
 * <li>任务事件(tasks 通知/stream)→ {@link #pubAllTasks}:广播到所有连接的 tasks 频道
 *     (apiKey 只认证、不决定业务归属,不做 owner 隔离);</li>
 * <li>RPC 应答 → 请求来源连接(RpcContext 绑定 conn,前端只听自己 hub 的 evt 频道);</li>
 * <li>evt 频道通知(config/fs/workspaces.changed)→ {@link #broadcastEvt}:每条连接各自的 evt 频道。</li>
 * </ul>
 * 单连接故障不影响其余;全断时任务照跑落盘(磁盘是真相源),重连后前端 sync 补齐。
 */
@Component
public class HubPool {

    /** 池级监听器:回调携带来源连接。 */
    public interface Listener {
        default void onHubConnected(HubLink conn) {
        }

        default void onHubDisconnected(HubLink conn) {
        }

        default void onHubMessage(HubLink conn, JsonNode msgFrame) {
        }
    }

    private static final Logger log = LoggerFactory.getLogger(HubPool.class);

    private final WorkerProperties props;
    private final List<HubLink> conns = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public HubPool(WorkerProperties props) {
        this.props = props;
    }

    @PostConstruct
    void start() {
        List<WorkerProperties.HubConfig> hubs = props.resolveHubs();
        for (int i = 0; i < hubs.size(); i++) {
            WorkerProperties.HubConfig h = hubs.get(i);
            HubLink link = new HubLink("hub" + i, h.getUrl(), h.getApiKey(), props.getWorkerId(),
                    h.getHubKey(), props.getHubInitialBackoffMs(), props.getHubMaxBackoffMs());
            link.addListener(new HubLink.Listener() {
                @Override
                public void onHubConnected() {
                    for (Listener l : listeners) {
                        try {
                            l.onHubConnected(link);
                        } catch (RuntimeException e) {
                            log.error("onHubConnected 监听器异常", e);
                        }
                    }
                }

                @Override
                public void onHubDisconnected() {
                    for (Listener l : listeners) {
                        try {
                            l.onHubDisconnected(link);
                        } catch (RuntimeException e) {
                            log.error("onHubDisconnected 监听器异常", e);
                        }
                    }
                }

                @Override
                public void onHubMessage(JsonNode msgFrame) {
                    for (Listener l : listeners) {
                        try {
                            l.onHubMessage(link, msgFrame);
                        } catch (RuntimeException e) {
                            log.error("onHubMessage 监听器异常", e);
                        }
                    }
                }
            });
            conns.add(link);
        }
        log.info("hub 池就绪:{} 条连接,workerId={}", conns.size(), props.getWorkerId());
        for (HubLink c : conns) {
            c.start();
        }
    }

    @PreDestroy
    void stop() {
        for (HubLink c : conns) {
            c.stop();
        }
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    /** 全部连接(配置期固定,顺序即配置序)。 */
    public List<HubLink> conns() {
        return List.copyOf(conns);
    }

    /** 首个连接(迁移归属等"主配置"语义)。 */
    public HubLink primary() {
        return conns.get(0);
    }

    /** 至少一条连接健康。 */
    public boolean anyConnected() {
        for (HubLink c : conns) {
            if (c.isConnected()) {
                return true;
            }
        }
        return false;
    }

    /** 扇出发布:发给所有 ownerKey 匹配的连接(不匹配的 hub 频道名不同也无权发)。 */
    public void pubForOwner(String k, String channel, String event, Long seq, JsonNode payload, JsonNode ext) {
        for (HubLink c : conns) {
            if (c.k().equals(k)) {
                c.pub(channel, event, seq, payload, ext);
            }
        }
    }

    /** 任务事件广播:发到每条连接各自的 tasks 频道(全部连接可见,不做 owner 扇出)。 */
    public void pubAllTasks(String event, Long seq, JsonNode payload, JsonNode ext) {
        for (HubLink c : conns) {
            c.pub(Channels.tasks(c.k()), event, seq, payload, ext);
        }
    }

    /** evt 频道通知:每条连接各自命名空间下的 worker evt 频道(任意 hub 上的前端都能收到)。 */
    public void broadcastEvt(String event, JsonNode payload) {
        for (HubLink c : conns) {
            c.pub(Channels.workerEvt(c.k(), c.workerId()), event, null, payload, null);
        }
    }
}
