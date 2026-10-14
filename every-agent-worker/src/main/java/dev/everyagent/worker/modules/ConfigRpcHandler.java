package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.UserPreferenceStore;
import dev.everyagent.worker.proto.ConfigDtos.ModelConfig;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.exception.NotFoundException;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.skill.Skill;
import dev.everyagent.worker.skill.SkillCatalog;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置与技能 RPC（基础设施层）：config.get / config.reload /
 * skill.list / skill.exclusions.set / pref.get / pref.set。
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
    private final SkillCatalog skillCatalog;
    private final SkillExclusionStore skillExclusions;
    private final WorkspaceManager workspaces;
    private final UserPreferenceStore preferences;
    private final HubPool pool;
    private final RpcDispatcher dispatcher;

    public ConfigRpcHandler(ConfigStore configs,
                            SkillCatalog skillCatalog,
                            SkillExclusionStore skillExclusions,
                            WorkspaceManager workspaces,
                            UserPreferenceStore preferences,
                            HubPool pool,
                            RpcDispatcher dispatcher) {
        this.configs = configs;
        this.skillCatalog = skillCatalog;
        this.skillExclusions = skillExclusions;
        this.workspaces = workspaces;
        this.preferences = preferences;
        this.pool = pool;
        this.dispatcher = dispatcher;
    }

    @PostConstruct
    void init() {
        dispatcher.register(RpcMethods.CONFIG_GET, this::rpcConfigGet);
        dispatcher.register(RpcMethods.CONFIG_RELOAD, this::rpcConfigReload);
        dispatcher.register(RpcMethods.SKILL_LIST, this::rpcSkillList);
        dispatcher.register(RpcMethods.SKILL_EXCLUSIONS_SET, this::rpcSkillExclusionsSet);
        dispatcher.register(RpcMethods.PREF_GET, this::rpcPrefGet);
        dispatcher.register(RpcMethods.PREF_SET, this::rpcPrefSet);
    }

    private void rpcConfigGet(RpcContext ctx) {
        ArrayNode arr = Json.arr();
        for (ModelConfig c : configs.list()) {
            ModelConfig safe = new ModelConfig(c.configId(), c.provider(), c.baseUrl(), c.fullUrl(),
                    c.model(), c.apiKey() == null || c.apiKey().isEmpty() ? null : "******",
                    c.params(), c.isDefault());
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
     * config.reload:重新读取模型配置(架构 §5.5)。
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
     * skill.list:返回该 worker 的全量 skill(数据源 {@link SkillCatalog#scan()},目录即真相源,
     * 内置/插件包与用户自制一并计入)+ 各自「不自动暴露的工作区」排除表
     * (数据源 {@link SkillExclusionStore#snapshot()})。设置页「Skill 列表」表格的数据源。
     *
     * <p>wire 形态(注意 name 对应 {@link Skill#title()} 记录字段——wire 字段名以计划为准用 name):
     * {@code [{id, name, description, knowledgePath, excludedWorkspaces:[...]}]}。
     * 排除表只描述「不自动暴露的工作区」,不影响 {@code /} 菜单(菜单永远全量)。
     */
    void rpcSkillList(RpcContext ctx) {
        Map<String, Set<String>> snap = skillExclusions.snapshot();
        ArrayNode skills = Json.arr();
        for (Skill s : skillCatalog.scan()) {
            ArrayNode excluded = Json.arr();
            snap.getOrDefault(s.id(), Set.of()).forEach(excluded::add);
            skills.add(Json.obj()
                    .put("id", s.id())
                    .put("name", s.title())
                    .put("description", s.description())
                    .put("knowledgePath", s.knowledgePath())
                    .set("excludedWorkspaces", excluded));
        }
        ctx.ok(skills);
    }

    /**
     * skill.exclusions.set(架构 §7.17 暴露控制,参数 {@code skillId} + {@code workspaces:[root,...]}):
     * 覆盖式设置某 skill「不自动暴露的工作区」黑名单并原子落盘,成功后广播
     * {@code config.changed{keys:["skills"]}}(前端表格与 {@code /} 菜单刷新;运行中任务不受
     * 影响——skill 注入发生在 run 开始时)。<b>空数组 = 恢复全部工作区暴露</b>。
     *
     * <p>校验(防「永不生效的排除」):
     * <ul>
     *   <li>{@code skillId} 必须存在于当前 catalog(实时 {@link SkillCatalog#scan()} 比对),
     *       不存在 → {@link NotFoundException}(NOT_FOUND);</li>
     *   <li>{@code workspaces} 每项必须是该 worker <b>已注册</b>的工作区 root
     *       ({@link WorkspaceManager#list()} 比对,两边 {@code toAbsolutePath().normalize()}
     *       后字符串相等),未注册项 → {@link BadParamsException}(BAD_PARAMS)——防拼写错配出
     *       「永不生效的排除」;<b>例外</b>:已存在于该 skill 当前排除表中的 root 放行
     *       (工作区被删后前端「保存时原样保留旧排除项」的回写不能被卡死,旧项可再次
     *       落盘也可通过不再携带而清除)。</li>
     * </ul>
     * 已注册但后来被删的工作区留在配置里不报错(不生效但无害,前端展示时过滤)。
     * normalize 后的 root 才落盘,与 {@code WorkspaceManager} 注册表键同形态。
     */
    void rpcSkillExclusionsSet(RpcContext ctx) {
        String skillId = ctx.strParam("skillId");
        JsonNode wsParam = ctx.params().path("workspaces");
        if (!wsParam.isArray()) {
            throw new BadParamsException("缺少参数 workspaces(工作区根路径数组)");
        }
        boolean known = skillCatalog.scan().stream().anyMatch(s -> skillId.equals(s.id()));
        if (!known) {
            throw new NotFoundException("skill 不存在: " + skillId);
        }
        Set<String> registered = new HashSet<>();
        for (WorkspaceManager.Registered r : workspaces.list()) {
            registered.add(Path.of(r.root()).toAbsolutePath().normalize().toString());
        }
        List<String> roots = new ArrayList<>();
        Set<String> current = skillExclusions.excludedWorkspaces(skillId);
        for (JsonNode n : wsParam) {
            if (!n.isTextual() || n.asString().isBlank()) {
                throw new BadParamsException("workspaces 每项须为非空的工作区根路径字符串: " + n);
            }
            String raw = n.asString().trim();
            Path in;
            try {
                in = Path.of(raw);
            } catch (RuntimeException e) {
                throw new BadParamsException("工作区路径非法: " + raw);
            }
            if (!in.isAbsolute()) {
                throw new BadParamsException("工作区必须是 worker 所在机器的绝对路径: " + raw);
            }
            String norm = in.toAbsolutePath().normalize().toString();
            if (!registered.contains(norm) && !current.contains(norm)) {
                throw new BadParamsException("工作区未注册(排除不会生效): " + raw);
            }
            if (!roots.contains(norm)) {
                roots.add(norm);
            }
        }
        skillExclusions.setExcludedWorkspaces(skillId, roots);
        pool.broadcastEvt(Events.CONFIG_CHANGED,
                Json.toJson(new Events.ConfigChanged(List.of("skills"))));
        ArrayNode excluded = Json.arr();
        skillExclusions.excludedWorkspaces(skillId).forEach(excluded::add);
        log.info("skill.exclusions.set 完成: {} 排除 {} 个工作区(已写入 skill-exclusions.json)",
                skillId, excluded.size());
        ctx.ok(Json.obj().put("skillId", skillId).set("excludedWorkspaces", excluded));
    }

    /**
     * pref.get:返回全部用户偏好(key-value),如 {theme:"dark"}。
     * 前端连接 worker 后据此同步主题等偏好(持久化在 preferences.json)。
     */
    private void rpcPrefGet(RpcContext ctx) {
        ObjectNode out = Json.obj();
        for (var entry : preferences.getAll().entrySet()) {
            out.put(entry.getKey(), entry.getValue());
        }
        ctx.ok(out);
    }

    /**
     * pref.set:写入单个偏好(参数 key + value),原子落盘后广播
     * config.changed{keys:["preferences"]} 通知所有客户端同步。
     */
    private void rpcPrefSet(RpcContext ctx) {
        String key = ctx.optStrParam("key", "");
        String value = ctx.optStrParam("value", "");
        if (key.isEmpty()) {
            ctx.err("BAD_PARAMS", "缺少参数 key");
            return;
        }
        preferences.set(key, value);
        pool.broadcastEvt(Events.CONFIG_CHANGED,
                Json.toJson(new Events.ConfigChanged(List.of("preferences"))));
        log.info("pref.set: {} = {} (已写入 preferences.json)", key, value);
        ctx.ok(Json.obj().put("key", key).put("value", value));
    }
}
