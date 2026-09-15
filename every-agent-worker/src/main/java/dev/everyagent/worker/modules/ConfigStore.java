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
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final WorkerProperties props;

    public ConfigStore(WorkerProperties props, HubPool pool) {
        this.props = props;
        rebuild(props.getModels());
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

    /** 解析池成员 configId 列表(逗号分隔,trim/去空/去重,顺序保持;首个 = 主模型)。
     *  成员 config-id 不存在(笔误/漏配)为<b>非致命</b>配置错误:跳过该成员并 error 告警,
     *  仅当池因此无任何有效成员时才抛 IllegalStateException(见下方)。 */
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
                // 池成员 config-id 不存在(笔误/漏配)= 非致命配置错误:跳过该成员并 error 告警,
                // 让 worker 仍可启动(容灾池本意即「单成员不可用不影响整体」);仅当池因此无任何
                // 有效成员时才拒绝启动(见下方 members.isEmpty() 分支)。避免一个成员笔误崩掉整个
                // worker、连配置修复界面都进不去的死循环。
                log.error("[config] worker.models[{}] 池成员 config-id 不存在,已跳过: {}",
                        configId, id);
                continue;
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

    /**
     * 重新读取模型配置(架构 §5.10):
     * 从 {@code <系统目录>/application-worker.yaml} 重新解析 {@code worker.models},
     * 如文件不存在或不含 models 段则保持当前配置不变(不因文件缺失而清空)。
     * 调用方负责在成功后广播 {@code config.changed} 事件通知前端刷新。
     *
     * @return 重载后的模型条目数
     */
    public synchronized int reload() {
        Path external = props.resolveHomeDir().resolve("application-worker.yaml");
        if (!Files.isRegularFile(external)) {
            log.info("[config] 重新读取模型配置:外部配置文件不存在({}),保持当前 {} 条配置", external, configs.size());
            return configs.size();
        }
        List<WorkerProperties.Model> models = parseExternalModels(external);
        if (models == null) {
            log.info("[config] 重新读取模型配置:外部文件不含 worker.models 段,保持当前 {} 条配置", configs.size());
            return configs.size();
        }
        // 同步回写 WorkerProperties,使后续 resolve 等路径与重建状态一致。
        props.setModels(models);
        rebuild(models);
        log.info("[config] 重新读取模型配置成功:共 {} 条", configs.size());
        return configs.size();
    }

    /** 从外部 YAML 文件解析 worker.models 段为 WorkerProperties.Model 列表;不含该段返回 null。 */
    @SuppressWarnings("unchecked")
    private List<WorkerProperties.Model> parseExternalModels(Path external) {
        try (InputStream in = Files.newInputStream(external)) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);
            if (root == null || root.isEmpty()) {
                return null;
            }
            Object workerNode = root.get("worker");
            if (!(workerNode instanceof Map<?, ?> workerMap)) {
                return null;
            }
            Object modelsNode = workerMap.get("models");
            if (!(modelsNode instanceof List<?> modelsList) || modelsList.isEmpty()) {
                return null;
            }
            List<WorkerProperties.Model> models = new ArrayList<>();
            for (Object item : modelsList) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                WorkerProperties.Model model = new WorkerProperties.Model();
                Object configId = m.get("config-id");
                if (configId != null) {
                    model.setConfigId(String.valueOf(configId));
                }
                Object provider = m.get("provider");
                if (provider != null) {
                    model.setProvider(String.valueOf(provider));
                }
                Object baseUrl = m.get("base-url");
                if (baseUrl != null) {
                    model.setBaseUrl(String.valueOf(baseUrl));
                }
                Object modelField = m.get("model");
                if (modelField != null) {
                    model.setModel(String.valueOf(modelField));
                }
                Object apiKey = m.get("api-key");
                if (apiKey != null) {
                    model.setApiKey(String.valueOf(apiKey));
                }
                Object params = m.get("params");
                if (params instanceof Map<?, ?> paramsMap) {
                    Map<String, Object> paramsOut = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> pe : paramsMap.entrySet()) {
                        paramsOut.put(String.valueOf(pe.getKey()), pe.getValue());
                    }
                    model.setParams(paramsOut);
                }
                Object isDefault = m.get("is-default");
                if (isDefault instanceof Boolean b) {
                    model.setIsDefault(b);
                }
                models.add(model);
            }
            return models;
        } catch (IOException e) {
            log.error("[config] 读取外部配置文件失败: {}", external, e);
            throw new IllegalStateException("读取模型配置文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 WorkerProperties.Model 列表重建内部不可变配置索引。
     * 校验 configId 唯一、池配置成员合法性;失败抛 IllegalStateException(调用方据上下文决定是否致命)。
     */
    private synchronized void rebuild(List<WorkerProperties.Model> models) {
        configs.clear();
        byId.clear();
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
