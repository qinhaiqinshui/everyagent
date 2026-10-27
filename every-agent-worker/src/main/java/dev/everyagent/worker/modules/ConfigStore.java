package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.proto.ConfigDtos.ModelConfig;
import dev.everyagent.plugin.api.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型配置存储(架构 §5.5「config.get」/§7.17「配置分层」):数据源 = Spring 配置 {@code worker.models}
 * (jar 内 application.yml 默认 + ~/.everyagent/application-worker.yaml 用户覆盖)。
 * 模型配置是系统功能,不进工作区、不随工作区切换;任务创建时解析为不可变快照
 * (apiKey 只留内存,不入事件日志)。写入口(设置页「模型配置」表格):addModel/removeModel
 * 把整表落盘到 {@code <系统目录>/application-worker.yaml} 的 {@code worker.models} 段
 * (保留文件内其他配置键),成功后热重建内存索引,由调用方广播 config.changed{keys:["models"]}。
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
        return new ModelConfig(configId, provider, m.getBaseUrl(), m.getFullUrl(),
                m.getModel() == null ? "" : m.getModel(), m.getApiKey(), paramsNode, m.getIsDefault());
    }


    public synchronized List<ModelConfig> list() {
        return new ArrayList<>(configs);
    }

    /**
     * 重新读取模型配置(架构 §5.5「config.reload」):
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

    /**
     * 新增模型配置(设置页「模型配置」表格的添加入口):校验 configId 唯一后,
     * 把「当前配置整表 + 新条目」落盘到 {@code <系统目录>/application-worker.yaml}
     * 的 {@code worker.models} 段(保留文件内其他配置键),再热重建内存索引。
     * 调用方负责广播 config.changed{keys:["models"]}。
     *
     * @return 落盘后的模型条目总数
     * @throws IllegalArgumentException configId 为空/重复、模型名缺失,或 base-url/full-url 未二选一
     */
    public synchronized int addModel(WorkerProperties.Model model) {
        validateModelFields(model);
        String id = model.getConfigId().trim();
        model.setConfigId(id);
        List<WorkerProperties.Model> current = props.getModels() == null
                ? new ArrayList<>() : new ArrayList<>(props.getModels());
        for (WorkerProperties.Model m : current) {
            if (m != null && m.getConfigId() != null && m.getConfigId().trim().equals(id)) {
                throw new IllegalArgumentException("config-id 已存在: " + id);
            }
        }
        current.add(model);
        writeModelsToExternal(current);
        props.setModels(current);
        rebuild(current);
        log.info("[config] 新增模型配置: {}(共 {} 条,已落盘 application-worker.yaml)", id, current.size());
        return current.size();
    }

    /**
     * 删除模型配置(设置页「模型配置」表格的删除入口):把「当前配置整表 − 目标条目」
     * 落盘到外部 YAML 并热重建。条目不存在时抛 {@link NotFoundException}。
     * 调用方负责广播 config.changed{keys:["models"]}。
     *
     * @return 落盘后的模型条目总数
     */
    public synchronized int removeModel(String configId) {
        String id = configId == null ? "" : configId.trim();
        if (id.isEmpty()) {
            throw new NotFoundException("模型配置不存在: " + configId);
        }
        List<WorkerProperties.Model> current = props.getModels() == null
                ? new ArrayList<>() : new ArrayList<>(props.getModels());
        boolean removed = current.removeIf(m -> m != null && m.getConfigId() != null && m.getConfigId().trim().equals(id));
        if (!removed) {
            throw new NotFoundException("模型配置不存在: " + id);
        }
        writeModelsToExternal(current);
        props.setModels(current);
        rebuild(current);
        log.info("[config] 删除模型配置: {}(剩 {} 条,已落盘 application-worker.yaml)", id, current.size());
        return current.size();
    }

    /**
     * 更新模型配置(设置页「模型配置」表格的编辑入口):按 {@code configId} 定位已存在条目,
     * 用传入值整条覆盖并落盘 + 热重建。{@code apiKey} 留空/缺省 = 保持原 key 不变
     * (明文不出 worker,前端仅见掩码);{@code params}/{@code isDefault} 缺省同样保留原值,
     * 其余字段按传入值覆盖。条目不存在 → {@link NotFoundException}。
     * 调用方负责广播 config.changed{keys:["models"]}。
     *
     * @return 落盘后的模型条目总数
     */
    public synchronized int updateModel(WorkerProperties.Model model) {
        validateModelFields(model);
        String id = model.getConfigId().trim();
        model.setConfigId(id);
        List<WorkerProperties.Model> current = props.getModels() == null
                ? new ArrayList<>() : new ArrayList<>(props.getModels());
        int idx = -1;
        for (int i = 0; i < current.size(); i++) {
            WorkerProperties.Model m = current.get(i);
            if (m != null && m.getConfigId() != null && m.getConfigId().trim().equals(id)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new NotFoundException("模型配置不存在: " + id);
        }
        WorkerProperties.Model existing = current.get(idx);
        // apiKey 留空/缺省 → 保持原 key(前端仅见掩码,无法回填明文)。
        if (model.getApiKey() == null || model.getApiKey().isEmpty()) {
            model.setApiKey(existing.getApiKey());
        }
        // params/isDefault 缺省 → 保留原值(编辑表单不暴露这两项)。
        if (model.getParams() == null || model.getParams().isEmpty()) {
            model.setParams(existing.getParams());
        }
        if (model.getIsDefault() == null) {
            model.setIsDefault(existing.getIsDefault());
        }
        current.set(idx, model);
        writeModelsToExternal(current);
        props.setModels(current);
        rebuild(current);
        log.info("[config] 更新模型配置: {}(共 {} 条,已落盘 application-worker.yaml)", id, current.size());
        return current.size();
    }

    /**
     * 模型配置字段校验(add/update 共用):configId 非空、model 非空、
     * baseUrl/fullUrl 二选一(都填或都不填 → IllegalArgumentException)。
     */
    private static void validateModelFields(WorkerProperties.Model model) {
        if (model == null || model.getConfigId() == null || model.getConfigId().isBlank()) {
            throw new IllegalArgumentException("config-id 不能为空");
        }
        if (model.getModel() == null || model.getModel().isBlank()) {
            throw new IllegalArgumentException("模型名不能为空");
        }
        boolean hasBaseUrl = model.getBaseUrl() != null && !model.getBaseUrl().isBlank();
        boolean hasFullUrl = model.getFullUrl() != null && !model.getFullUrl().isBlank();
        if (hasBaseUrl && hasFullUrl) {
            throw new IllegalArgumentException("base-url 与 full-url 只能填一项");
        }
        if (!hasBaseUrl && !hasFullUrl) {
            throw new IllegalArgumentException("base-url 与 full-url 必须填一项");
        }
    }

    /**
     * 把模型整表写入 {@code <系统目录>/application-worker.yaml} 的 {@code worker.models} 段:
     * 文件已存在时保留其中其他配置键(注释不保留),不存在时新建;写 tmp 再原子移动落盘。
     */
    @SuppressWarnings("unchecked")
    private void writeModelsToExternal(List<WorkerProperties.Model> models) {
        Path external = props.resolveHomeDir().resolve("application-worker.yaml");
        Map<String, Object> root = new LinkedHashMap<>();
        if (Files.isRegularFile(external)) {
            try (InputStream in = Files.newInputStream(external)) {
                Object loaded = new Yaml().load(in);
                if (loaded instanceof Map<?, ?> loadedMap) {
                    for (Map.Entry<?, ?> e : loadedMap.entrySet()) {
                        root.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
            } catch (IOException e) {
                log.error("[config] 读取外部配置文件失败: {}", external, e);
                throw new IllegalStateException("读取模型配置文件失败: " + e.getMessage(), e);
            }
        }
        Object workerNode = root.get("worker");
        Map<String, Object> workerMap = new LinkedHashMap<>();
        if (workerNode instanceof Map<?, ?> existingWorker) {
            for (Map.Entry<?, ?> e : existingWorker.entrySet()) {
                workerMap.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        workerMap.put("models", toModelYamlList(models));
        root.put("worker", workerMap);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        options.setIndent(2);
        String text = new Yaml(options).dump(root);
        try {
            Files.createDirectories(external.getParent());
            Path tmp = external.resolveSibling("application-worker.yaml.tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, external, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("[config] 写入外部配置文件失败: {}", external, e);
            throw new IllegalStateException("写入模型配置文件失败: " + e.getMessage(), e);
        }
    }

    /** 模型列表 → YAML 节点(kebab-case 键,与 application-worker.yaml 手写格式一致;空值键省略)。 */
    private static List<Map<String, Object>> toModelYamlList(List<WorkerProperties.Model> models) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorkerProperties.Model m : models) {
            if (m == null) {
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>();
            if (m.getConfigId() != null && !m.getConfigId().isBlank()) {
                node.put("config-id", m.getConfigId());
            }
            if (m.getProvider() != null && !m.getProvider().isBlank()) {
                node.put("provider", m.getProvider());
            }
            if (m.getBaseUrl() != null && !m.getBaseUrl().isBlank()) {
                node.put("base-url", m.getBaseUrl());
            }
            if (m.getFullUrl() != null && !m.getFullUrl().isBlank()) {
                node.put("full-url", m.getFullUrl());
            }
            if (m.getModel() != null && !m.getModel().isBlank()) {
                node.put("model", m.getModel());
            }
            if (m.getApiKey() != null && !m.getApiKey().isEmpty()) {
                node.put("api-key", m.getApiKey());
            }
            if (m.getParams() != null && !m.getParams().isEmpty()) {
                node.put("params", new LinkedHashMap<>(m.getParams()));
            }
            if (m.getIsDefault() != null) {
                node.put("is-default", m.getIsDefault());
            }
            out.add(node);
        }
        return out;
    }


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
                Object fullUrl = m.get("full-url");
                if (fullUrl != null) {
                    model.setFullUrl(String.valueOf(fullUrl));
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
                c.baseUrl(), c.fullUrl(), c.model(), c.params());
        return new ResolvedConfig(snap, apiKeyOf(c));
    }

    private static String apiKeyOf(ModelConfig c) {
        return c.apiKey() == null ? "" : c.apiKey();
    }
}
