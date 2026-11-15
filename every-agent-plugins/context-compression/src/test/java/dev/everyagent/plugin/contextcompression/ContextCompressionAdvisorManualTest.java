package dev.everyagent.plugin.contextcompression;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.config.WorkerConfig.Limits;
import dev.everyagent.plugin.api.event.Usage;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import dev.everyagent.plugin.api.slash.SlashTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ContextCompressionAdvisor} 手动压缩路径单测。
 *
 * <p>验证 /压缩上下文 斜杠命令触发的一次性强制压缩:
 * <ul>
 *   <li>token 存在时绕过触发阈值强制压缩;</li>
 *   <li>压缩完成后 token 被消费(removeKind 调用);</li>
 *   <li>token 不存在时走正常自动压缩路径(不调 removeKind)。</li>
 * </ul>
 */
class ContextCompressionAdvisorManualTest {

    /** 构造一条足够大的上下文(历史轮含工具调用),使手动压缩有内容可压。 */
    private static List<Message> buildContext() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("system prompt"));
        // 历史轮:user + assistant(toolCall) + toolResult + final
        msgs.add(new UserMessage("历史问题 " + "x".repeat(600)));
        msgs.add(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "read_file", "{}")))
                .build());
        msgs.add(ToolResponseMessageBuilder.build("c1", "read_file", "x".repeat(600)));
        msgs.add(AssistantMessage.builder().content("历史最终回复 " + "x".repeat(600)).build());
        // 本轮
        msgs.add(new UserMessage("本轮问题 " + "x".repeat(300)));
        msgs.add(AssistantMessage.builder().content("本轮回复 " + "x".repeat(300)).build());
        return msgs;
    }

    private static ChatClientRequest buildRequest(List<Message> messages) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(messages))
                .context(Map.of())
                .build();
    }

    private static ExecContext mockExec(String taskId, long window) {
        ExecContext exec = mock(ExecContext.class);
        when(exec.subjectId()).thenReturn(taskId);
        ModelConfig snapshot = mock(ModelConfig.class);
        when(snapshot.contextWindowTokens()).thenReturn(window);
        when(exec.snapshot()).thenReturn(snapshot);
        return exec;
    }

    private static AgentContext mockAgent(ExecContext exec) {
        AgentContext a = mock(AgentContext.class);
        when(a.execution()).thenReturn(exec);
        when(a.agentId()).thenReturn("main");
        when(a.currentModel()).thenReturn("test-model");
        when(a.lastModel()).thenReturn(null);
        when(a.lastRound()).thenReturn(null);
        when(a.fullViewEstTokens()).thenReturn(0L);
        // emitter 吞掉所有 emit 调用
        EventEmitter emitter = mock(EventEmitter.class);
        when(a.emitter()).thenReturn(emitter);
        return a;
    }

    private static Limits mockLimits(boolean enabled) {
        Limits limits = mock(Limits.class);
        when(limits.contextCompressionEnabled()).thenReturn(enabled);
        when(limits.contextTriggerRatio()).thenReturn(0.95);
        when(limits.contextTargetRatio()).thenReturn(0.50);
        when(limits.contextSafetyRatio()).thenReturn(1.0);
        when(limits.contextToolReserveTokens()).thenReturn(1024L);
        when(limits.contextMaxToolResultChars()).thenReturn(40000);
        when(limits.contextOffsetEnabled()).thenReturn(false);
        when(limits.contextSummaryEnabled()).thenReturn(false);
        return limits;
    }

    @Test
    void manualCompressForcesCompressionBelowThreshold() {
        // 上下文远未达到触发阈值(窗口 100 万 × 0.95 = 95 万),但手动 token 存在 → 强制压缩。
        List<Message> msgs = buildContext();
        ChatClientRequest request = buildRequest(msgs);

        ExecContext exec = mockExec("task-1", 1_000_000);
        AgentContext a = mockAgent(exec);
        Limits limits = mockLimits(true);

        SlashTokenService slashTokens = mock(SlashTokenService.class);
        when(slashTokens.hasKind("task-1", CompressContextToken.KIND)).thenReturn(true);
        when(slashTokens.removeKind("task-1", CompressContextToken.KIND)).thenReturn(true);

        ContextCompressionAdvisor advisor = new ContextCompressionAdvisor(
                a, limits, null, null, slashTokens);

        AtomicReference<ChatClientRequest> captured = new AtomicReference<>();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any(ChatClientRequest.class))).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return mock(org.springframework.ai.chat.client.ChatClientResponse.class);
        });

        advisor.adviseCall(request, chain);

        ChatClientRequest sent = captured.get();
        assertNotNull(sent, "请求应传给下游");
        List<Message> out = sent.prompt().getInstructions();
        String joined = out.toString();
        assertTrue(joined.contains(ContextCompressor.PLACEHOLDER) || out.size() < msgs.size(),
                "手动压缩应改写 instructions(工具调用被占位替换或消息减少)");
        // 首条应为压缩提示 system message
        assertTrue(out.get(0) instanceof SystemMessage
                        && out.get(0).getText().contains("早期上下文已被压缩"),
                "压缩后应注入系统提示");
        // token 被消费
        verify(slashTokens, times(1)).removeKind("task-1", CompressContextToken.KIND);
    }

    @Test
    void manualCompressConsumesTokenEvenWhenNoChangeNeeded() {
        // 极简上下文(无历史轮工具调用)→ 压缩器可能返回 compressed=true 但消息未实质改变;
        // 仍然必须消费 token(一次性,不残留)。
        List<Message> msgs = new ArrayList<>();
        msgs.add(new UserMessage("hello"));
        ChatClientRequest request = buildRequest(msgs);

        ExecContext exec = mockExec("task-2", 1_000_000);
        AgentContext a = mockAgent(exec);
        Limits limits = mockLimits(true);

        SlashTokenService slashTokens = mock(SlashTokenService.class);
        when(slashTokens.hasKind("task-2", CompressContextToken.KIND)).thenReturn(true);

        ContextCompressionAdvisor advisor = new ContextCompressionAdvisor(
                a, limits, null, null, slashTokens);

        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any(ChatClientRequest.class)))
                .thenReturn(mock(org.springframework.ai.chat.client.ChatClientResponse.class));
        advisor.adviseCall(request, chain);

        verify(slashTokens, times(1)).removeKind("task-2", CompressContextToken.KIND);
    }

    @Test
    void noManualTokenDoesNotCallRemoveKind() {
        // 无手动 token → 正常路径,不调 removeKind
        List<Message> msgs = buildContext();
        ChatClientRequest request = buildRequest(msgs);

        ExecContext exec = mockExec("task-3", 1_000_000);
        AgentContext a = mockAgent(exec);
        Limits limits = mockLimits(true);

        SlashTokenService slashTokens = mock(SlashTokenService.class);
        when(slashTokens.hasKind(anyString(), anyString())).thenReturn(false);

        ContextCompressionAdvisor advisor = new ContextCompressionAdvisor(
                a, limits, null, null, slashTokens);

        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any(ChatClientRequest.class)))
                .thenReturn(mock(org.springframework.ai.chat.client.ChatClientResponse.class));
        advisor.adviseCall(request, chain);

        verify(slashTokens, never()).removeKind(anyString(), anyString());
    }

    @Test
    void manualCompressWorksEvenWhenAutoDisabled() {
        // 自动压缩整体关闭(context-compression-enabled=false),但用户显式请求 → 仍压缩。
        List<Message> msgs = buildContext();
        ChatClientRequest request = buildRequest(msgs);

        ExecContext exec = mockExec("task-4", 1_000_000);
        AgentContext a = mockAgent(exec);
        Limits limits = mockLimits(false); // 自动压缩关闭

        SlashTokenService slashTokens = mock(SlashTokenService.class);
        when(slashTokens.hasKind("task-4", CompressContextToken.KIND)).thenReturn(true);
        when(slashTokens.removeKind("task-4", CompressContextToken.KIND)).thenReturn(true);

        ContextCompressionAdvisor advisor = new ContextCompressionAdvisor(
                a, limits, null, null, slashTokens);

        AtomicReference<ChatClientRequest> captured = new AtomicReference<>();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any(ChatClientRequest.class))).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return mock(org.springframework.ai.chat.client.ChatClientResponse.class);
        });
        advisor.adviseCall(request, chain);

        ChatClientRequest sent = captured.get();
        assertNotNull(sent);
        List<Message> out = sent.prompt().getInstructions();
        String joined = out.toString();
        assertTrue(joined.contains(ContextCompressor.PLACEHOLDER) || out.size() < msgs.size(),
                "自动压缩关闭时手动压缩仍应生效");
        verify(slashTokens, times(1)).removeKind("task-4", CompressContextToken.KIND);
    }

    @Test
    void nullSlashTokensDisablesManualCompress() {
        // slashTokens 为 null(旧构造/无 slash 服务)→ 不触发手动压缩,走正常路径。
        List<Message> msgs = buildContext();
        ChatClientRequest request = buildRequest(msgs);

        ExecContext exec = mockExec("task-5", 1_000_000);
        AgentContext a = mockAgent(exec);
        Limits limits = mockLimits(true);

        // 4 参构造(无 slashTokens)
        ContextCompressionAdvisor advisor = new ContextCompressionAdvisor(
                a, limits, null, null);

        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any(ChatClientRequest.class)))
                .thenReturn(mock(org.springframework.ai.chat.client.ChatClientResponse.class));
        advisor.adviseCall(request, chain);
        // 不会 NPE,正常返回
    }

    @Test
    void compressContextTokenBuildsValidOpaqueToken() {
        String token = CompressContextToken.buildToken();
        SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(token);
        assertNotNull(parsed);
        assertEquals(CompressContextToken.KIND, parsed.kind());
        assertEquals("压缩上下文", parsed.label());
        assertTrue(parsed.payload().path("enabled").asBoolean());
    }

    @Test
    void compressContextSlashResolverReturnsEmpty() {
        CompressContextSlashResolver resolver = new CompressContextSlashResolver();
        assertEquals(CompressContextToken.KIND, resolver.kind());
        assertEquals("", resolver.resolveSubmissionText(Json.obj()));
    }

    @Test
    void slashProviderReturnsItemWithCorrectId() {
        var items = CompressContextSlashProvider.items(mock(dev.everyagent.plugin.api.WorkerServices.class));
        assertEquals(1, items.size());
        var item = items.get(0);
        assertEquals("compress-context:now", item.id());
        assertEquals("压缩上下文", item.title());
        assertFalse(item.defaultSelected(), "不应默认选中(一次性命令)");
        // insertText 是合法 opaque token
        SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(item.insertText());
        assertNotNull(parsed);
        assertEquals(CompressContextToken.KIND, parsed.kind());
    }

    /** ToolResponseMessage 构造辅助(避免 import 冗余)。 */
    private static class ToolResponseMessageBuilder {
        static Message build(String id, String name, String data) {
            return org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                    .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                            id, name, data)))
                    .build();
        }
    }
}
