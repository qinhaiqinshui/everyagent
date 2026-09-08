package dev.everyagent.worker.web;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 本机健康检查(9200 仅监听 127.0.0.1)。 */
@RestController
public class HealthController {

    private final HubPool pool;
    private final WorkerProperties props;

    public HealthController(HubPool pool, WorkerProperties props) {
        this.pool = pool;
        this.props = props;
    }

    @GetMapping("/health")
    public ObjectNode health() {
        ObjectNode o = Json.obj();
        o.put("status", "UP");
        o.put("workerId", props.getWorkerId());
        o.put("hubConnected", pool.anyConnected());
        ArrayNode conns = o.putArray("hubs");
        pool.conns().forEach(c -> conns.add(Json.obj()
                .put("name", c.name())
                .put("url", c.url())
                .put("connected", c.isConnected())));
        return o;
    }
}
