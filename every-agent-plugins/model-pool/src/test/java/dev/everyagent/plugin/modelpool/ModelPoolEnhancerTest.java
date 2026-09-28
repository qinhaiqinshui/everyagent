package dev.everyagent.plugin.modelpool;

import dev.everyagent.plugin.api.model.EnhancedChatModel;
import dev.everyagent.plugin.api.model.EnhancerContext;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.MemberSpec;
import dev.everyagent.plugin.api.model.ModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * ModelPoolEnhancer 单测：验证插件自行解析成员格式的各项行为。
 *
 * <p>使用 fake EnhancerContext，不需要真实 ChatModelFactory。
 * buildMember 返回 mock ChatModel（带预设的 OpenAiChatOptions）。
 */
class ModelPoolEnhancerTest {

    // ---- 测试用 fake / 辅助 ----

    /** 记录 buildMember 调用顺序的 fake context。 */
    private static class FakeContext implements EnhancerContext {
        final ModelConfig poolCfg;
        final Map<String, MemberSpec> members = new HashMap<>();
        final java.util.List<String> buildOrder = new java.util.ArrayList<>();

        FakeContext(String poolModel) {
            this.poolCfg = new ModelConfig("pool-1", "model-pool", null, poolModel, null);
        }

        void put(String configId, String provider, String model) {
            members.put(configId, new MemberSpec(
                    new ModelConfig(configId, provider, "http://x", model, null), "key-" + configId));
        }

        @Override
        public ModelConfig poolConfig() { return poolCfg; }

        @Override
        public String agentId() { return "test-agent"; }

        @Override
        public EventEmitter events() { return null; }

        @Override
        public MemberSpec resolveMember(String configId) {
            return members.get(configId);
        }

        @Override
        public ChatModel buildMember(MemberSpec member) {
            buildOrder.add(member.config().configId());
            return memberChatModel(member.config().configId(), member.config().model());
        }
    }

    private static ChatModel memberChatModel(String configId, String model) {
        ChatModel mock = mock(ChatModel.class);
        OpenAiChatOptions opts = OpenAiChatOptions.builder()
                .model(model).baseUrl("http://x").apiKey("k-" + configId).build();
        org.mockito.Mockito.when(mock.getOptions()).thenReturn(opts);
        return mock;
    }

    // ---- 测试 ----

    @Test
    void parseCommaSeparatedIdsPreserveOrder() {
        FakeContext ctx = new FakeContext("a,b,c");
        ctx.put("a", "openai-compat", "model-a");
        ctx.put("b", "openai-compat", "model-b");
        ctx.put("c", "openai-compat", "model-c");

        EnhancedChatModel result = new ModelPoolEnhancer().enhance(ctx);

        assertNotNull(result.chatModel());
        assertEquals(3, ctx.buildOrder.size());
        assertEquals("a", ctx.buildOrder.get(0));
        assertEquals("b", ctx.buildOrder.get(1));
        assertEquals("c", ctx.buildOrder.get(2));
    }

    @Test
    void trimAndSkipEmptyParts() {
        FakeContext ctx = new FakeContext(" a , b ,, c ");
        ctx.put("a", "openai-compat", "model-a");
        ctx.put("b", "openai-compat", "model-b");
        ctx.put("c", "openai-compat", "model-c");

        new ModelPoolEnhancer().enhance(ctx);

        assertEquals(3, ctx.buildOrder.size());
        assertEquals("a", ctx.buildOrder.get(0));
        assertEquals("b", ctx.buildOrder.get(1));
        assertEquals("c", ctx.buildOrder.get(2));
    }

    @Test
    void deduplicateIds() {
        FakeContext ctx = new FakeContext("a,a,b,a");
        ctx.put("a", "openai-compat", "model-a");
        ctx.put("b", "openai-compat", "model-b");

        new ModelPoolEnhancer().enhance(ctx);

        assertEquals(2, ctx.buildOrder.size());
        assertEquals("a", ctx.buildOrder.get(0));
        assertEquals("b", ctx.buildOrder.get(1));
    }

    @Test
    void skipMissingMembers() {
        FakeContext ctx = new FakeContext("a,missing,b");
        ctx.put("a", "openai-compat", "model-a");
        ctx.put("b", "openai-compat", "model-b");
        // "missing" 未 put → resolveMember 返回 null

        EnhancedChatModel result = new ModelPoolEnhancer().enhance(ctx);

        assertNotNull(result.chatModel());
        assertEquals(2, ctx.buildOrder.size());
        assertEquals("a", ctx.buildOrder.get(0));
        assertEquals("b", ctx.buildOrder.get(1));
    }

    @Test
    void rejectNestedPoolMember() {
        FakeContext ctx = new FakeContext("a,bad");
        ctx.put("a", "openai-compat", "model-a");
        ctx.put("bad", "model-pool", "nested");   // 成员 provider 是 model-pool

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ModelPoolEnhancer().enhance(ctx));
        assertNestedPoolMsg(e, "bad");
    }

    private static void assertNestedPoolMsg(IllegalStateException e, String configId) {
        // 确认异常信息包含"禁池套池"和对应 configId
        String msg = e.getMessage();
        assertNotNull(msg);
        assert msg.contains("禁池套池") : "异常应提示禁池套池";
        assert msg.contains(configId) : "异常应包含 configId: " + configId;
    }

    @Test
    void allMembersMissingThrows() {
        FakeContext ctx = new FakeContext("x,y,z");
        // 不 put 任何成员 → 全部 resolveMember 返回 null

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ModelPoolEnhancer().enhance(ctx));
        assertNotNull(e.getMessage());
        assert e.getMessage().contains("无有效成员") : "异常应提示无有效成员";
    }

    @Test
    void nullModelFieldThrows() {
        FakeContext ctx = new FakeContext(null);
        assertThrows(IllegalStateException.class, () -> new ModelPoolEnhancer().enhance(ctx));
    }

    @Test
    void blankModelFieldThrows() {
        FakeContext ctx = new FakeContext("   ");
        assertThrows(IllegalStateException.class, () -> new ModelPoolEnhancer().enhance(ctx));
    }

    @Test
    void primaryOptionsFromFirstMember() {
        FakeContext ctx = new FakeContext("a,b");
        ctx.put("a", "openai-compat", "model-a");
        ctx.put("b", "openai-compat", "model-b");

        EnhancedChatModel result = new ModelPoolEnhancer().enhance(ctx);

        ChatOptions opts = result.primaryOptions();
        assertInstanceOf(OpenAiChatOptions.class, opts);
        assertEquals("model-a", ((OpenAiChatOptions) opts).getModel());
    }
}
