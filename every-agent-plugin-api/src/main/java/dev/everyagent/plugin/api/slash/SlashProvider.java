package dev.everyagent.plugin.api.slash;

import java.util.List;

/**
 * Slash 命令提供者接口 —— 插件经 {@code WorkerPluginContext.registerSlashProvider} 注册。
 *
 * <p>返回 {@link SlashCommandItem} 列表，此接口使 plugin-api 不依赖 worker 的 slash 基础设施类型。
 */
@FunctionalInterface
public interface SlashProvider {

    /**
     * 加载该来源的全部条目。
     *
     * @return slash 命令条目列表
     */
    List<SlashCommandItem> load();
}
