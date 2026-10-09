package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.util.AtomicFiles;
import dev.everyagent.worker.config.WorkerProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户偏好持久化存储(通用 key-value):落盘 {@code <系统目录>/preferences.json}。
 *
 * <p>设计目标:通用的用户偏好容器,主题(theme)只是其中一个 key;
 * 后续其他用户级偏好(语言、字体大小等)可直接复用,无需新增存储。
 *
 * <p>非工作区级配置——不随工作区切换、不进任务数据;与 application-worker.yaml
 * (系统配置,只读)区别:本存储是用户可写的运行时偏好,由 {@code pref.get}/{@code pref.set}
 * RPC 读写,worker 为唯一事实源。浏览器/桌面/多端经 RPC 统一存取,不再依赖 localStorage origin。
 *
 * <p>线程安全:所有读写均 synchronized。磁盘写入用「临时文件 + rename」原子操作。
 */
@Component
public class UserPreferenceStore {

    private static final Logger log = LoggerFactory.getLogger(UserPreferenceStore.class);

    /** 默认偏好(启动时文件不存在 / 解析失败时回退)。 */
    private static final Map<String, String> DEFAULTS = Map.of("theme", "light");

    private final WorkerProperties props;
    private final Map<String, String> prefs = new LinkedHashMap<>();
    private Path filePath;

    public UserPreferenceStore(WorkerProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        filePath = props.resolveHomeDir().resolve("preferences.json");
        load();
    }

    /** 从磁盘加载;文件不存在或解析失败时回退默认值。 */
    private void load() {
        prefs.clear();
        prefs.putAll(DEFAULTS);
        if (!Files.isRegularFile(filePath)) {
            log.info("[prefs] preferences.json 不存在({}),使用默认值", filePath);
            return;
        }
        try {
            String content = Files.readString(filePath);
            if (content.isBlank()) {
                return;
            }
            JsonNode root = Json.parse(content);
            if (!root.isObject()) {
                log.warn("[prefs] preferences.json 根节点非 object,使用默认值");
                return;
            }
            for (var entry : root.properties()) {
                if (entry.getValue().isTextual()) {
                    prefs.put(entry.getKey(), entry.getValue().asString());
                }
            }
        } catch (IOException e) {
            log.warn("[prefs] 读取 preferences.json 失败,使用默认值: {}", e.getMessage());
        }
    }

    /** 读取指定 key;不存在返回 defaultValue。 */
    public synchronized String get(String key, String defaultValue) {
        return prefs.getOrDefault(key, defaultValue);
    }

    /** 读取全部偏好(快照副本)。 */
    public synchronized Map<String, String> getAll() {
        return new LinkedHashMap<>(prefs);
    }

    /** 写入指定 key-value:更新内存 + 原子写回磁盘。 */
    public synchronized void set(String key, String value) {
        prefs.put(key, value);
        flush();
    }

    /** 将当前内存状态原子写入磁盘(临时文件 + rename)。 */
    private void flush() {
        try {
            Path dir = filePath.getParent();
            Files.createDirectories(dir);
            ObjectNode root = Json.obj();
            for (var entry : prefs.entrySet()) {
                root.put(entry.getKey(), entry.getValue());
            }
            AtomicFiles.writeText(filePath, Json.write(root)); // 唯一名 tmp + 原子替换(失败已清理,不残留垃圾)
        } catch (IOException e) {
            log.error("[prefs] 写入 preferences.json 失败: {}", e.getMessage(), e);
            throw new IllegalStateException("写入用户偏好失败: " + e.getMessage(), e);
        }
    }
}
