package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.proto.ConfigDtos.ModelConfig;
import dev.everyagent.worker.proto.Events;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.skill.ExternalSkillScanner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 配置与技能 RPC（基础设施层）：config.get / config.reload / skill.reload。
 * 从 TaskManager 迁出——这些 RPC 与任务编排无关，属基础设施的配置面。
 * 自己在 @PostConstruct 中注册到 RpcDispatcher（与 task.* 平权注册）。
 *
 * <p>注意：TaskManager 侧对这三个方法的注册行（registerMethods 中）在后续步骤删除；
 * 当前 {@link RpcDispatcher#register} 为覆盖式（Map.put，后注册者赢），过渡期两边注册
 * 同构方法体，无论 @PostConstruct 先后顺序如何均无功能差异。
 */
@Component
public class ConfigRpcHandler {

    private static final Logger log = LoggerFactory.getLogger(ConfigRpcHandler.class);

    private final ConfigStore configs;
    private final ExternalSkillScanner externalSkillScanner;
    private final HubPool pool;
    private final RpcDispatcher dispatcher;

    public ConfigRpcHandler(ConfigStore configs,
                            ExternalSkillScanner externalSkillScanner,
                            HubPool pool,
                            RpcDispatcher dispatcher) {
        this.configs = configs;
        this.externalSkillScanner = externalSkillScanner;
        this.pool = pool;
        this.dispatcher = dispatcher;
    }

    @PostConstruct
    void init() {
        dispatcher.register(RpcMethods.CONFIG_GET, this::rpcConfigGet);
        dispatcher.register(RpcMethods.CONFIG_RELOAD, this::rpcConfigReload);
        dispatcher.register(RpcMethods.SKILL_RELOAD, this::rpcSkillReload);
    }

    private void rpcConfigGet(RpcContext ctx) {
        ArrayNode arr = Json.arr();
        for (ModelConfig c : configs.list()) {
            ModelConfig safe = new ModelConfig(c.configId(), c.provider(), c.baseUrl(),
                    c.model(), c.apiKey() == null || c.apiKey().isEmpty() ? null : "******",
                    c.params(), c.isDefault(), c.members());
            arr.add(Json.toJson(safe));
        }
        ObjectNode out = Json.obj().set("models", arr);
        // 限流运行态已由 model-rate-limit 插件管理（步骤 4 搬迁）。
        // worker 核心不再持有 ModelRateLimiterRegistry，rateStatus 暂返回空数组。
        // 遗留项：插件后续自行注册 RPC 透出限流快照（步骤 5）。
        out.set("rateStatus", Json.arr());
        ctx.ok(out);
    }

    /**
     * config.reload:重新读取模型配置(架构 §5.10)。
     * 从 {@code <系统目录>/application-worker.yaml} 重新解析 worker.models,
     * 成功后广播 config.changed{keys:["models"]} 通知前端刷新。
     * 仅影响后续新建任务的模型解析;运行中任务使用创建时冻结的快照,不受影响。
     */
    private void rpcConfigReload(RpcContext ctx) {
        int count = configs.reload();
        // 广播 config.changed 通知前端 modelConfigs 服务自动刷新模型列表。
        pool.broadcastEvt(Events.CONFIG_CHANGED,
                Json.toJson(new Events.ConfigChanged(List.of("models"))));
        log.info("config.reload 完成:模型配置已重新加载,共 {} 条", count);
        ctx.ok(Json.obj().put("models", count));
    }

    /**
     * skill.reload:重新扫描外部 skill 列表(架构 §7.17)。
     * 用户在系统技能目录下增删 skill 目录后,点击「重新读取」即可让 worker 热加载,
     * 无需重启。广播 {@code config.changed{keys:["skills"]}} 通知前端刷新 `/` 菜单。
     * 运行中任务不受影响(skill 列表只在新建任务的 `/` 菜单与 SkillAdvisor 注入时读取)。
     */
    private void rpcSkillReload(RpcContext ctx) {
        int count = externalSkillScanner.reload();
        pool.broadcastEvt(Events.CONFIG_CHANGED,
                Json.toJson(new Events.ConfigChanged(List.of("skills"))));
        log.info("skill.reload 完成:外部 skill 已重新扫描,共 {} 个", count);
        ctx.ok(Json.obj().put("skills", count));
    }
}
