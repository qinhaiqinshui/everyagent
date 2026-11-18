package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.spi.FileReadHandler;
import dev.everyagent.plugin.api.spi.FileReadResult;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FileReadHandlerRegistry「按扩展名一对一映射 + 大小写归一 + 覆盖」的回归测试
 * (模式与 FileReferenceHandlerRegistry 一致)。
 *
 * <p>锁死三条语义:
 * <ol>
 *   <li>注册键与查询键统一小写含点——注册 {@code .PNG} 也能按 {@code .png} 查到;</li>
 *   <li>同扩展名后注册覆盖先注册(一对一映射,后写者赢);</li>
 *   <li>未注册/空扩展名查询返回 null;isEmpty 反映注册表是否为空。</li>
 * </ol>
 */
class FileReadHandlerRegistryTest {

    @Test
    void registerThenFindByLowercasedExtension() {
        FileReadHandlerRegistry registry = new FileReadHandlerRegistry();
        assertTrue(registry.isEmpty(), "初始为空");

        FileReadHandler images = stub("images", Set.of(".PNG", ".Jpg")); // 注册侧大写也要归一
        registry.register(images);

        assertFalse(registry.isEmpty());
        assertSame(images, registry.find(".png"), "注册键大写已归一,小写可查到");
        assertSame(images, registry.find(".jpg"));
        assertNull(registry.find(".txt"), "未注册扩展名返回 null");
        assertNull(registry.find(""), "空扩展名返回 null");
        assertNull(registry.find(null), "null 扩展名返回 null");
    }

    @Test
    void sameExtensionLaterRegistrationOverridesEarlier() {
        FileReadHandlerRegistry registry = new FileReadHandlerRegistry();
        FileReadHandler first = stub("first", Set.of(".png"));
        FileReadHandler second = stub("second", Set.of(".png"));
        registry.register(first);
        registry.register(second); // 同扩展名后注册覆盖先注册(打 WARN)

        assertSame(second, registry.find(".png"), "后注册者覆盖先注册者");
    }

    @Test
    void malformedRegistrationsAreIgnored() {
        FileReadHandlerRegistry registry = new FileReadHandlerRegistry();
        registry.register(null);                       // null handler
        registry.register(stub("bad", null));          // null 扩展名集合
        registry.register(stub("empty", Set.of()));    // 空扩展名集合
        registry.register(stub("blanks", Set.of("", ".tif"))); // 空串扩展名被跳过,非空项照常登记

        assertNotNull(registry.find(".tif"), "集合中的合法扩展名仍登记");
        assertNull(registry.find(".png"));
        assertFalse(registry.isEmpty());
    }

    /** 最小 stub:只关心 pluginId/extensions(read 返回与 id 同名的占位结果)。 */
    static FileReadHandler stub(String pluginId, Set<String> extensions) {
        return new FileReadHandler() {
            @Override
            public String pluginId() {
                return pluginId;
            }

            @Override
            public Set<String> extensions() {
                return extensions;
            }

            @Override
            public FileReadResult read(ExecContext ctx, String path, Path file) {
                return new FileReadResult(pluginId, List.of());
            }
        };
    }
}
