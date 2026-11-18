package dev.everyagent.plugin.contextcompression;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CompressContextRequestNode} + {@link ManualCompressRegistry} +
 * {@link CompressContextToken#scanRequest} 单测:inline 胶囊提交 → 请求登记一次性生命周期。
 */
class CompressContextRequestNodeTest {

    /** 构造一个 raw 为「文字 + 压缩胶囊」的提交原文(模拟前端 rawContent)。 */
    private static String rawWithToken() {
        return "帮我看下这个问题 " + CompressContextToken.buildToken();
    }

    @Test
    void scanRequestDetectsTokenInRawContent() {
        assertTrue(CompressContextToken.scanRequest(rawWithToken()), "rawContent 含压缩胶囊应命中");
        assertTrue(CompressContextToken.scanRequest(CompressContextToken.buildToken()), "纯胶囊也应命中");
        assertFalse(CompressContextToken.scanRequest("普通消息,无胶囊"), "无胶囊不命中");
        assertFalse(CompressContextToken.scanRequest(null), "null 安全");
        assertFalse(CompressContextToken.scanRequest(""), "空串安全");
        // 其他 kind 的 opaque token 不误命中
        String other = dev.everyagent.plugin.api.slash.SlashTokenEncoder.buildToken(
                "other.kind", "别的胶囊", null, dev.everyagent.contract.json.Json.obj());
        assertFalse(CompressContextToken.scanRequest("前缀 " + other + " 后缀"), "异 kind 不命中");
    }

    @Test
    void nodeMarksRegistryWhenTokenPresent() throws Exception {
        ManualCompressRegistry registry = new ManualCompressRegistry();
        CompressContextRequestNode node = new CompressContextRequestNode(registry);

        TaskLifecycleContext ctx = mock(TaskLifecycleContext.class);
        when(ctx.taskId()).thenReturn("task-1");
        when(ctx.rawContent()).thenReturn(rawWithToken());
        TaskChain chain = mock(TaskChain.class);

        node.invoke(ctx, chain);

        assertTrue(registry.has("task-1"), "扫到胶囊应登记请求");
        verify(chain).proceed(ctx);
    }

    @Test
    void nodeSkipsWhenNoTokenOrEmptyRaw() throws Exception {
        ManualCompressRegistry registry = new ManualCompressRegistry();
        CompressContextRequestNode node = new CompressContextRequestNode(registry);

        TaskLifecycleContext ctx = mock(TaskLifecycleContext.class);
        when(ctx.taskId()).thenReturn("task-2");
        when(ctx.rawContent()).thenReturn("普通消息");
        TaskChain chain = mock(TaskChain.class);
        node.invoke(ctx, chain);
        assertFalse(registry.has("task-2"), "无胶囊不应登记");

        when(ctx.rawContent()).thenReturn(null);
        node.invoke(ctx, chain);
        assertFalse(registry.has("task-2"), "rawContent 为 null 不应登记");
    }

    @Test
    void registryIsOneShot() {
        ManualCompressRegistry registry = new ManualCompressRegistry();
        registry.mark("task-3");
        registry.mark("task-3"); // 幂等:重复 mark 不叠加
        assertTrue(registry.has("task-3"));
        registry.consume("task-3");
        assertFalse(registry.has("task-3"), "消费后请求即消失(一次性)");
        registry.consume("task-3"); // 幂等消费不抛错
        registry.mark(null); // null 防御
        assertFalse(registry.has(null));
    }
}
