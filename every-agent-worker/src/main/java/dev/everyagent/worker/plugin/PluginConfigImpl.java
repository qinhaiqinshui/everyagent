package dev.everyagent.worker.plugin;

import java.util.Map;

/**
 * PluginConfig 实现 —— 从 plugin.json contributes.config 读取配置项。
 *
 * <p>阶段二：从内存 Map 读取（plugin.json 解析时填充默认值）。
 * 后续可扩展为从用户设置文件读取覆盖值。
 */
public class PluginConfigImpl implements PluginConfig {

    private final Map<String, Object> values;

    public PluginConfigImpl(Map<String, Object> values) {
        this.values = values != null ? values : Map.of();
    }

    @Override
    public String getString(String key, String defaultValue) {
        Object v = values.get(key);
        return v != null ? v.toString() : defaultValue;
    }

    @Override
    public int getInt(String key, int defaultValue) {
        Object v = values.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        Object v = values.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return defaultValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type, T defaultValue) {
        Object v = values.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (type.isInstance(v)) {
            return (T) v;
        }
        return defaultValue;
    }
}
