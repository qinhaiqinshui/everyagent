package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.spi.FileReadHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FileReadHandler SPI 注册表（read_file 按扩展名接管）。
 *
 * <p>模式同 {@link FileReferenceHandlerRegistry}：注册时机为插件
 * activate() 时经 {@code WorkerPluginContext.registerFileReadHandler} 注册。
 * 扩展名 → handler 一对一映射（小写含点），同扩展名后注册覆盖先注册并打 WARN。
 *
 * <p>消费方：{@code FileTools.read_file}——扩展名命中 handler 时核心先行授权解析
 * （READ 档 + 沙箱 jail）再把宿主绝对路径交处理器；未命中或处理器返回 null
 * 回落核心默认文本读取，行为不变。
 */
@Component
public class FileReadHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(FileReadHandlerRegistry.class);

    /** 扩展名（小写含点）→ handler。 */
    private final Map<String, FileReadHandler> byExtension = new ConcurrentHashMap<>();

    /** 注册一个 FileReadHandler（按其声明的全部扩展名登记）。 */
    public void register(FileReadHandler handler) {
        if (handler == null || handler.extensions() == null) {
            return;
        }
        for (String ext : handler.extensions()) {
            if (ext == null || ext.isEmpty()) {
                continue;
            }
            String key = ext.toLowerCase(Locale.ROOT);
            FileReadHandler prev = byExtension.put(key, handler);
            if (prev != null && prev != handler) {
                log.warn("[read-file] 扩展名 {} 的处理器被覆盖: {} → {}", key, prev.pluginId(), handler.pluginId());
            }
        }
    }

    /** 按扩展名（小写含点）查 handler；未注册返回 null。 */
    public FileReadHandler find(String extension) {
        if (extension == null || extension.isEmpty()) {
            return null;
        }
        return byExtension.get(extension);
    }

    /** 是否没有任何已注册 handler（read_file 据此快速短路）。 */
    public boolean isEmpty() {
        return byExtension.isEmpty();
    }
}
