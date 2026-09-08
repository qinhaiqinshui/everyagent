package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.proto.ConfigDtos.ModelConfig;
import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import dev.everyagent.worker.rpc.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型配置存储(架构 §5.10,config.get 只读):数据源 = Spring 配置 {@code worker.models}
 * (jar 内 application.yml 默认 + ~/.everyagent/application-worker.yaml 用户覆盖)。
 * 模型配置是系统功能,不进工作区、不随工作区切换;任务创建时解析为不可变快照
 * (apiKey 只留内存,不入事件日志)。本类不再持久化、不再提供写入口(前端只读)。
 *
 * <p>{@code provider: model-pool} 是「容灾池」配置:其 {@code model} 字段为逗号分隔的
 * 池成员 configId 列表(首个 = 主模型),{@link #resolve} 时解析出各成员的
 * {@link ResolvedConfig} 填入 {@link ResolvedConfig#poolMembers()},供
 * {@code ChatModelFactory} 构建 {@code ModelPoolChatModel}。池成员必须是普通模型(禁池套池)。
 */
@Component
public class ConfigStore {

    /** 容灾池专用 provider 取值。 */
    public static final String POOL_PROVIDER = "model-pool";

    public record ResolvedConfig(ModelSnapshot snapshot, String apiKey, List<ResolvedConfig> poolMembers) {
        public ResolvedConfig(ModelSnapshot snapshot, String apiKey) {
            this(snapshot, apiKey, List.of());
        }

        /** 是否为容灾池配置(provider=model-pool 且已解析出成员)。 */
        public boolean isPool() {
            return POOL_PROVIDER.equals(snapshot.provider()) && !poolMembers.isEmpty();
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    private final List<ModelConfig> configs = new ArrayList<>();
    private final Map<String, ModelConfig> byId = new LinkedHashMap<>();

    public ConfigStore(WorkerProperties props, HubPool pool) {
        List<WorkerProperties.Model> models = props.getModels();
        if (models == null || models.isEmpty()) {
            log.warn("[config] worker.models 为空,无可用模型配置(config.get 将返回空列表)");
            return;
        }
        // 先建 id → 配置 索引并校验 configId 唯一,供池成员校验与 resolve 使用。
        Map<String, WorkerProperties.Model> index = new LinkedHashMap<>();
        for (WorkerProperties.Model m : models) {
            if (m == null || m.getConfigId() == null || m.getConfigId().isBlank()) {
                throw new IllegalStateException("worker.models 存在缺 config-id 的条目");
            }
            String id = m.getConfigId().trim();
            if (index.containsKey(id)) {
                throw new IllegalStateException("worker.models config-id 重复: " + id);
            }
            index.put(id, m);
        }
        for (Map.Entry<String, WorkerProperties.Model> e : index.entrySet()) {
            ModelConfig c = toConfig(e.getValue(), index);
            configs.add(c);
            byId.put(c.configId(), c);
        }
    }

    private static ModelConfig toConfig(WorkerProperties.Model m, Map<String, WorkerProperties.Model> index) {
        Map<String, Object> params = m.getParams();
        JsonNode paramsNode = (params == null || params.isEmpty()) ? null : Json.toJson(params);
        String provider = m.getProvider() == null ? "" : m.getProvider().trim();
        String configId = m.getConfigId().trim();
        if (POOL_PROVIDER.equals(provider)) {
            List<String> members = parsePoolMembers(m, index);
            // 池外壳只声明成员列表、自身不配 params;「有效参数」= 主模型(首成员)。
            // 此处继承首成员 params 到池 ModelConfig/config.get 视图,保证窗口大小
            // (contextWindowTokens)等实际生效参数在池配置上可读,与运行时快照一致;
            // 池外壳显式配置了 params 时以其为准(用户显式覆盖整个池)。
            if (paramsNode == null && !members.isEmpty()) {
                WorkerProperties.Model primary = index.get(members.get(0));
                if (primary != null && primary.getParams() != null && !primary.getParams().isEmpty()) {
                    paramsNode = Json.toJson(primary.getParams());
                }
            }
            return new ModelConfig(configId, provider, m.getBaseUrl(),
                    m.getModel(), m.getApiKey(), paramsNode, m.getIsDefault(), members);
        }
        if (m.getModel() == null || m.getModel().isBlank()) {
            throw new IllegalStateException("worker.models[" + configId + "] 普通模型缺 model");
        }
        return new ModelConfig(configId, provider, m.getBaseUrl(),
                m.getModel(), m.getApiKey(), paramsNode, m.getIsDefault(), null);
    }

    /** 解析池成员 configId 列表(逗号分隔,trim/去空/去重,顺序保持;首个 = 主模型)。 */
    private static List<String> parsePoolMembers(WorkerProperties.Model m,
            Map<String, WorkerProperties.Model> index) {
        String configId = m.getConfigId().trim();
        String raw = m.getModel();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("worker.models[" + configId
                    + "] 池配置缺 model(逗号分隔的池成员 configId 列表)");
        }
        List<String> members = new ArrayList<>();
        for (String part : raw.split(",")) {
            String id = part.trim();
            if (id.isEmpty()) {
                continue;
            }
            WorkerProperties.Model member = index.get(id);
            if (member == null) {
                throw new IllegalStateException("worker.models[" + configId
                        + "] 池成员 config-id 不存在: " + id);
            }
            String mp = member.getProvider() == null ? "" : member.getProvider().trim();
            if (POOL_PROVIDER.equals(mp)) {
                throw new IllegalStateException("worker.models[" + configId
                        + "] 池成员不得是池配置(禁池套池): " + id);
            }
            if (members.contains(id)) {
                continue; // 去重
            }
            members.add(id);
        }
        if (members.isEmpty()) {
            throw new IllegalStateException("worker.models[" + configId + "] 池配置无有效成员");
        }
        return members;
    }

    public synchronized List<ModelConfig> list() {
        return new ArrayList<>(configs);
    }

    /** configId 为空取默认;找不到抛 NOT_FOUND。 */
    public synchronized ResolvedConfig resolve(String configId) {
        ModelConfig hit = null;
        for (ModelConfig c : configs) {
            if (configId == null || configId.isEmpty()) {
                if (Boolean.TRUE.equals(c.isDefault())) {
                    hit = c;
                    break;
                }
            } else if (c.configId().equals(configId)) {
                hit = c;
                break;
            }
        }
        if (hit == null && (configId == null || configId.isEmpty())) {
            hit = configs.isEmpty() ? null : configs.get(0);
        }
        if (hit == null) {
            throw new NotFoundException("模型配置不存在: " + configId);
        }
        return resolveConfig(hit);
    }

    /** 由 ModelConfig 构造 ResolvedConfig;池配置递归解析各成员。 */
    private ResolvedConfig resolveConfig(ModelConfig c) {
        // 池配置(provider=model-pool)的 params 已在 toConfig 继承主模型(首成员)的
        // 有效参数(contextWindowTokens 等),此处直接用 ModelConfig.params() 即可。
        ModelSnapshot snap = new ModelSnapshot(c.configId(), c.provider(),
                c.baseUrl(), c.model(), c.params());
        if (c.members() != null && !c.members().isEmpty()) {
            List<ResolvedConfig> members = new ArrayList<>(c.members().size());
            for (String id : c.members()) {
                ModelConfig mc = byId.get(id);
                if (mc != null) {
                    members.add(resolveConfig(mc));
                }
            }
            return new ResolvedConfig(snap, apiKeyOf(c), members);
        }
        return new ResolvedConfig(snap, apiKeyOf(c));
    }

    private static String apiKeyOf(ModelConfig c) {
        return c.apiKey() == null ? "" : c.apiKey();
    }
}
