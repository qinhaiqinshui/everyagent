package dev.everyagent.worker.plugin.loader;

import java.util.List;
import java.util.Map;

/**
 * plugin.json 解析模型(不可变 record)。
 * <p>
 * 插件清单格式示例见任务说明;字段缺失时由 {@link PluginDescriptorParser} 填充合理默认值
 * (空字符串 / 空 list / 空 map),本 record 不含 null。
 */
public record PluginDescriptor(
        String id,
        String name,
        String version,
        String description,
        String author,
        String minAppVersion,
        Requires requires,
        Provides provides,
        List<String> activationEvents,
        Contributes contributes
) {

    /** 依赖声明:SPI 接口列表 + every-agent 版本约束。 */
    public record Requires(
            List<String> spi,
            String everyAgent
    ) {
        public Requires {
            spi = spi == null ? List.of() : List.copyOf(spi);
            everyAgent = everyAgent == null ? "" : everyAgent;
        }
    }

    /** 提供声明:SPI 实现 / RPC 方法 / 斜杠命令 / Web 入口。 */
    public record Provides(
            Map<String, String> spi,
            List<String> rpc,
            List<String> slash,
            String web
    ) {
        public Provides {
            spi = spi == null ? Map.of() : Map.copyOf(spi);
            rpc = rpc == null ? List.of() : List.copyOf(rpc);
            slash = slash == null ? List.of() : List.copyOf(slash);
            web = web == null ? "" : web;
        }
    }

    /** 贡献声明:配置项表(config key → ConfigItem)。 */
    public record Contributes(
            Map<String, ConfigItem> config
    ) {
        public Contributes {
            config = config == null ? Map.of() : Map.copyOf(config);
        }
    }

    /** 单个配置项声明;JSON 中 {@code "default"} 映射为 {@link #defaultValue}。 */
    public record ConfigItem(
            String type,
            Object defaultValue,
            String description
    ) {
        public ConfigItem {
            type = type == null ? "" : type;
            description = description == null ? "" : description;
        }
    }
}
