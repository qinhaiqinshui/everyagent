package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.WorkerPluginContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * SubAgentPlugin.activate() 注册行为 + 声明式 skill 物化钉子。
 *
 * <p>c7322bf3 起 skill 物化从 activate() 移交 PluginLoader(读 plugin.json 的
 * {@code skills} 声明统一物化),本测试随之改钉两件事:
 * <ol>
 *   <li>{@code activate()} 注册 SubAgentToolsProvider + SubAgentSpawnedAwaitNode,
 *       不再承担物化(旧「activate 物化产物逐字节一致」断言随迁移作废);</li>
 *   <li>声明链完整:plugin.json 声明 {@code "skills": "skill"},插件根
 *       {@code skill/agent-dispatch/skill.md} 存在且 frontmatter 以 name 起——
 *       这是 hidden 工具机制的「另一半」:四工具不进请求 tool 定义,模型全靠这份
 *       文档认知,文档缺失即工具失明;全文行数钉 ≤120(skill 全文注入 system
 *       prompt,写散就是拿上下文换上下文)。测试工作目录 = 模块根(surefire 缺省)。</li>
 * </ol>
 */
class SubAgentPluginTest {

    @Test
    void activateRegistersToolProviderAndLifecycleNode() {
        WorkerPluginContext ctx = mock(WorkerPluginContext.class);

        new SubAgentPlugin().activate(ctx);

        verify(ctx).registerToolProvider(any(SubAgentToolsProvider.class));
        verify(ctx).registerTaskLifecycleNode(any(SubAgentSpawnedAwaitNode.class));
    }

    @Test
    void declaredSkillPackExistsCompactAndWellFormed() throws Exception {
        Path moduleRoot = Path.of("").toAbsolutePath(); // surefire 工作目录 = 模块根
        String json = Files.readString(moduleRoot.resolve("plugin.json"));
        assertTrue(json.contains("\"skills\"") && json.contains("\"skill\""),
                "plugin.json 应声明 skills 目录: " + json);

        Path skillMd = moduleRoot.resolve("skill").resolve("agent-dispatch").resolve("skill.md");
        assertTrue(Files.isRegularFile(skillMd), "声明式物化源应存在: " + skillMd);

        List<String> lines = Files.readAllLines(skillMd);
        assertTrue(lines.size() <= 120,
                "skill 全文注入 system prompt,须保持紧凑(计划钉 ≤120 行): 实际 " + lines.size());
        assertFalse(lines.isEmpty(), "skill.md 不应为空");

        // frontmatter 形态:--- 起,含 name(展示标题)
        String head = String.join("\n", lines.subList(0, Math.min(4, lines.size())));
        assertTrue(head.startsWith("---"), "skill.md 应以 frontmatter 块开头");
        assertTrue(head.contains("name: 子 Agent"), "frontmatter name 应为「子 Agent」");

        // hidden 工具的文档另一半:四工具名必须在文档中出现(模型认知的唯一来源)
        String body = String.join("\n", lines);
        for (String tool : List.of("run_agent", "wait_agents", "list_agents", "stop_agent")) {
            assertTrue(body.contains(tool), "skill.md 应文档化 hidden 工具: " + tool);
        }
    }
}
