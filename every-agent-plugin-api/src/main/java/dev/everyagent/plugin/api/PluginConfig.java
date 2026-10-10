package dev.everyagent.plugin.api;

/**
 * 插件配置 —— 从 plugin.json 的 contributes.config 解析。
 *
 * <p>对标 VSCode 的 {@code workspace.getConfiguration()}。
 * 插件在 plugin.json 中声明配置项，用户在设置中修改，
 * 插件代码经此接口读取配置值。
 */
public interface PluginConfig {

    /** 读取字符串配置项。 */
    String getString(String key, String defaultValue);

    /** 读取整数配置项。 */
    int getInt(String key, int defaultValue);

    /** 读取布尔配置项。 */
    boolean getBoolean(String key, boolean defaultValue);

    /** 读取任意类型配置项。 */
    <T> T get(String key, Class<T> type, T defaultValue);
}
