package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.proto.ConfigDtos.ModelConfig;
import dev.everyagent.plugin.api.exception.NotFoundException;
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
 * <p>组合 provider(如 model-pool)的成员解析由插件自行调用 {@link #resolveOrNull} 完成,
 * ConfigStore 不再感知「池」概念,统一按普通模型处理。
 */
@Component
public class ConfigStore {

    public record ResolvedConfig(dev.everyagent.plugin.api.model.ModelConfig snapshot,
            String apiKey) {
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
        return new ModelConfig(configId, provider, m.getBaseUrl(),
                m.getModel() == null ? "" : m.getModel(), m.getApiKey(), paramsNode, m.getIsDefault());
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

    /** 类似 {@link #resolve},但找不到配置时返回 null 而非抛异常。供插件 resolveMember 使用。 */
    public synchronized ResolvedConfig resolveOrNull(String configId) {
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
            return null;
        }
        return resolveConfig(hit);
    }

    /** 由 ModelConfig 构造 ResolvedConfig。 */
    private ResolvedConfig resolveConfig(ModelConfig c) {
        dev.everyagent.plugin.api.model.ModelConfig snap =
                new dev.everyagent.plugin.api.model.ModelConfig(c.configId(), c.provider(),
                c.baseUrl(), c.model(), c.params());
        return new ResolvedConfig(snap, apiKeyOf(c));
    }

    private static String apiKeyOf(ModelConfig c) {
        return c.apiKey() == null ? "" : c.apiKey();
    }
}
