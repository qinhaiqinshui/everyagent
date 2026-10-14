package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.config.WorkerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SubAgentPlugin.activate() 物化安装单测(「skill 目录唯一真相源」步骤 8 钉子)。
 *
 * <p>钉三件事:
 * <ol>
 *   <li>{@code activate()} 后 {@code <skillsDir>/agent-dispatch/skill.md} 存在,且与
 *       classpath 资源(本模块源码资源,目录形态)逐字节一致;</li>
 *   <li>skillsDir 下只有 {@code agent-dispatch}——不会出现 {@code plan/}、{@code skill-creator/}
 *       (worker 内置包住 {@code builtin-skill/} 根,插件扫 {@code skill/} 根,根名隔离)。
 *       <b>注意强弱形态</b>:本模块测试 classpath 不含 worker 资源(依赖只有 plugin-api /
 *       spring-ai-openai),故这条是弱形态;父委派隔离的强形态钉子在 plugin-api 的
 *       {@code SkillMaterializerTest}(构造 parent 含 worker 资源的 loader 复现
 *       PluginLoader 策略),worker 与插件同时在位的真机验证留给计划步骤 13;</li>
 *   <li>物化 IO 失败仅 WARN 降级,activate() 其余注册不受阻断。</li>
 * </ol>
 */
class SubAgentPluginTest {

    private static final String SKILL_RESOURCE = "skill/agent-dispatch/skill.md";

    @Test
    void activateMaterializesAgentDispatchPackIdenticalToClasspathResource(@TempDir Path skillsDir)
            throws IOException {
        new SubAgentPlugin().activate(context(skillsDir));

        Path materialized = skillsDir.resolve("agent-dispatch").resolve("skill.md");
        assertTrue(Files.isRegularFile(materialized), "物化产物应存在: " + materialized);

        byte[] onDisk = Files.readAllBytes(materialized);
        byte[] fromClasspath = readClasspathResource();
        assertArrayEquals(fromClasspath, onDisk, "物化产物须与 classpath 资源逐字节一致");

        // frontmatter 形态:文件以 --- 起,首个键为 name(展示标题与旧 SPI 注册的 title 一致)
        String head = new String(onDisk, 0, Math.min(onDisk.length, 256),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(head.startsWith("---"), "skill.md 应以 frontmatter 块开头");
        assertTrue(head.contains("name: 子 Agent"), "frontmatter name 应为「子 Agent」");

        // 隔离(弱形态,见类 javadoc):skillsDir 下只允许 agent-dispatch 一个条目
        try (Stream<Path> entries = Files.list(skillsDir)) {
            List<String> ids = entries.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(List.of("agent-dispatch"), ids,
                    "插件物化只应安装自己的包,不得出现 plan/、skill-creator/ 等 worker 内置包");
        }
    }

    @Test
    void activateDegradesGracefullyWhenMaterializationFails(@TempDir Path tmp) throws IOException {
        // skillsDir 指向一个普通文件 → createDirectories(<file>/agent-dispatch) 必抛 IOException
        Path blocker = Files.createFile(tmp.resolve("blocker.txt"));
        WorkerPluginContext ctx = context(blocker);

        new SubAgentPlugin().activate(ctx); // 不得抛出:物化失败仅 WARN 降级

        // 先于物化执行的工具注册不受阻断
        verify(ctx).registerToolProvider(org.mockito.ArgumentMatchers.any(SubAgentToolsProvider.class));
        verify(ctx).registerTaskLifecycleNode(org.mockito.ArgumentMatchers.any(SubAgentSpawnedAwaitNode.class));
    }

    /** 组装 activate() 所需的最小桩:只需 services().config().resolveSkillsDir()。 */
    private WorkerPluginContext context(Path skillsDir) {
        WorkerConfig config = mock(WorkerConfig.class);
        when(config.resolveSkillsDir()).thenReturn(skillsDir);
        WorkerServices services = mock(WorkerServices.class);
        when(services.config()).thenReturn(config);
        WorkerPluginContext ctx = mock(WorkerPluginContext.class);
        when(ctx.services()).thenReturn(services);
        return ctx;
    }

    private byte[] readClasspathResource() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SKILL_RESOURCE)) {
            assertTrue(in != null, "classpath 上应存在资源: " + SKILL_RESOURCE);
            return in.readAllBytes();
        }
    }
}
