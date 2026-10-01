package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.spi.FileReferenceHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FileReferenceHandler SPI 注册表。
 *
 * <p>模式同 SkillContributorRegistry / ToolProviderRegistry：注册时机为插件
 * activate() 时经 {@code WorkerPluginContext.registerFileReferenceHandler} 注册。
 * 扩展名 → handler 一对一映射（小写含点），同扩展名后注册覆盖先注册并打 WARN。
 *
 * <p>消费方：{@code FileReferenceProcessNode} 按文件扩展名分发文件引用。
 */
@Component
public class FileReferenceHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(FileReferenceHandlerRegistry.class);

    /** 扩展名（小写含点）→ handler。 */
    private final Map<String, FileReferenceHandler> byExtension = new ConcurrentHashMap<>();

    /** 注册一个 FileReferenceHandler（按其声明的全部扩展名登记）。 */
    public void register(FileReferenceHandler handler) {
        if (handler == null || handler.extensions() == null) {
            return;
        }
        for (String ext : handler.extensions()) {
            if (ext == null || ext.isEmpty()) {
                continue;
            }
            String key = ext.toLowerCase(Locale.ROOT);
            FileReferenceHandler prev = byExtension.put(key, handler);
            if (prev != null && prev != handler) {
                log.warn("[fileref] 扩展名 {} 的处理器被覆盖: {} → {}", key, prev.pluginId(), handler.pluginId());
            }
        }
    }

    /** 按扩展名（小写含点）查 handler；未注册返回 null。 */
    public FileReferenceHandler find(String extension) {
        if (extension == null || extension.isEmpty()) {
            return null;
        }
        return byExtension.get(extension);
    }

    /** 是否没有任何已注册 handler（节点据此快速短路）。 */
    public boolean isEmpty() {
        return byExtension.isEmpty();
    }
}
