package dev.everyagent.worker.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.spi.FileReadHandler;
import dev.everyagent.plugin.api.spi.FileReadResult;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.plugin.registry.FileReadHandlerRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * read_file 按扩展名接管({@code FileReadHandler},插件 SPI)的分发契约测试:
 * <ul>
 *   <li><b>命中 handler</b>:核心先行授权解析(READ 档)后把宿主绝对路径传入,
 *       {@code text} 直接作为工具结果,附件合并进 metadata.attachments 且<b>全等去重</b>
 *       (同一图片读两次不重复注入);</li>
 *   <li><b>handler 返回 null</b>:回落核心默认文本读取(行为与机制引入前一致);</li>
 *   <li><b>扩展名未命中</b>:默认文本读取照旧;</li>
 *   <li><b>注册表为 null(旧 3 参构造器)</b>:一切照旧。</li>
 * </ul>
 */
class FileToolsReadHandlerTest {

    @TempDir
    Path dir;

    private FsToolSupport fs;
    private ExecContext ctx;
    private final Map<String, Object> metadata = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        Path root = dir.toRealPath();

        WorkspaceManager ws = mock(WorkspaceManager.class);
        when(ws.resolve(anyString())).thenReturn(new WorkspaceManager.Root(root, root));
        when(ws.externalRootsOf(anyString())).thenReturn(List.of());

        WorkerProperties props = mock(WorkerProperties.class);
        when(props.resolveSkillsDir()).thenReturn(dir.resolve("__no_such_skills__"));

        HubPool pool = mock(HubPool.class);
        PermissionGate gate = mock(PermissionGate.class);
        when(gate.extraRoots(anyString())).thenReturn(List.of());

        fs = new FsToolSupport(ws, props, pool, gate);

        ctx = mock(ExecContext.class);
        when(ctx.subjectId()).thenReturn("t1");
        when(ctx.workspaceRoot()).thenReturn("ws");
        when(ctx.metadata()).thenReturn(metadata);
    }

    /** 固定图片附件 map(与 image-vision 附件结构一致)。 */
    private static Map<String, Object> imageAttachment() {
        Map<String, Object> att = new LinkedHashMap<>();
        att.put("type", "image");
        att.put("dataUrl", "data:image/png;base64,AAA");
        att.put("fileName", "a.png");
        att.put("mimeType", "image/png");
        return att;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> attachments() {
        Object atts = metadata.get(ExecContext.METADATA_ATTACHMENTS_KEY);
        return atts instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** 断言 handler 收到的 file 是核心授权解析后的宿主绝对路径。 */
    @Test
    void handlerTakesOverWithAttachmentDedup() throws Exception {
        Map<String, Object> attachment = imageAttachment();
        Path[] seenFile = new Path[1];
        String[] seenPath = new String[1];
        FileReadHandlerRegistry registry = new FileReadHandlerRegistry();
        registry.register(new FileReadHandler() {
            @Override
            public String pluginId() {
                return "img-stub";
            }

            @Override
            public Set<String> extensions() {
                return Set.of(".png");
            }

            @Override
            public FileReadResult read(ExecContext c, String path, Path file) {
                seenPath[0] = path;
                seenFile[0] = file;
                return new FileReadResult("IMG-OK", List.of(attachment));
            }
        });
        Files.write(dir.resolve("a.png"), new byte[] { 1, 2, 3 });
        FileTools tools = new FileTools(fs, ctx, "main", registry);

        assertEquals("IMG-OK", tools.read_file("a.png", null, null), "handler 结果文本直接回灌");
        assertEquals("a.png", seenPath[0], "原始 path 参数透传 handler");
        assertEquals(dir.toRealPath().resolve("a.png"), seenFile[0].toRealPath(),
                "file 为授权解析后的宿主绝对路径");
        assertEquals(1, attachments().size(), "附件恰好注入 1 项");

        // 再次 read 同一文件(handler 返回相同 map)→ 全等去重,附件仍 1 项
        assertEquals("IMG-OK", tools.read_file("a.png", null, null));
        assertEquals(1, attachments().size(), "相同附件不重复注入(去重生效)");
    }

    @Test
    void nullResultFallsBackToDefaultTextRead() throws Exception {
        Files.writeString(dir.resolve("b.png"), "伪 png 的真文本", StandardCharsets.UTF_8);
        FileReadHandlerRegistry registry = new FileReadHandlerRegistry();
        registry.register(new FileReadHandler() {
            @Override
            public String pluginId() {
                return "decline-stub";
            }

            @Override
            public Set<String> extensions() {
                return Set.of(".png");
            }

            @Override
            public FileReadResult read(ExecContext c, String path, Path file) {
                return null; // 放弃接管
            }
        });
        FileTools tools = new FileTools(fs, ctx, "main", registry);

        assertEquals("伪 png 的真文本", tools.read_file("b.png", null, null),
                "null 回落默认文本路径");
        assertEquals(List.of(), attachments(), "无附件写入");
    }

    @Test
    void unregisteredExtensionReadsTextAsBefore() throws Exception {
        Files.writeString(dir.resolve("c.txt"), "第1行\n第2行\n", StandardCharsets.UTF_8);
        FileReadHandlerRegistry registry = new FileReadHandlerRegistry();
        registry.register(new FileReadHandler() {
            @Override
            public String pluginId() {
                return "img-stub";
            }

            @Override
            public Set<String> extensions() {
                return Set.of(".png"); // .txt 未注册
            }

            @Override
            public FileReadResult read(ExecContext c, String path, Path file) {
                throw new IllegalStateException(".txt 不该派发给 .png handler");
            }
        });
        FileTools tools = new FileTools(fs, ctx, "main", registry);

        assertEquals("第1行\n第2行", tools.read_file("c.txt", null, null),
                "未命中扩展名走默认文本读取(与现状一致)");
    }

    @Test
    void nullRegistryBehavesAsBefore() throws Exception {
        Files.writeString(dir.resolve("d.png"), "文本 png", StandardCharsets.UTF_8);
        FileTools tools = new FileTools(fs, ctx, "main"); // 旧 3 参构造器(readHandlers=null)

        assertEquals("文本 png", tools.read_file("d.png", null, null), "无注册表时一切照旧");
    }

    /** 兜底:注册表存在但为空时同样短路(不触发任何 handler 派发)。 */
    @Test
    void emptyRegistryBehavesAsBefore() throws Exception {
        Files.writeString(dir.resolve("e.png"), "空注册表", StandardCharsets.UTF_8);
        FileReadHandlerRegistry registry = new FileReadHandlerRegistry();
        assertNull(registry.find(".png"));
        FileTools tools = new FileTools(fs, ctx, "main", registry);

        assertEquals("空注册表", tools.read_file("e.png", null, null));
    }
}
