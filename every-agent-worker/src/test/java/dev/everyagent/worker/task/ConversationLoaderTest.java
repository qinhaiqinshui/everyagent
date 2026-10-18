package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷启动会话重建:主 agent jsonl → Spring AI 消息序列。
 * 工具轮按 callId 配对;中断轮合成占位结果;thinking 不进 conversation;旧格式返回空。
 */
class ConversationLoaderTest {

    @TempDir
    Path dataDir;

    private static final String MAIN = "a_main1";

    private TaskStore store;
    private Path dir;

    @BeforeEach
    void setUp() throws Exception {
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(dataDir.toString());
        store = new TaskStore(props); // 读路径无状态,不必 start
        dir = dataDir.resolve("f".repeat(64)).resolve("t1");
        Files.createDirectories(dir);
    }

    private void write(String... lines) throws Exception {
        Files.writeString(dir.resolve(MAIN + ".jsonl"), String.join("\n", lines) + "\n");
    }

    @Test
    void echoRoundTrip() throws Exception {
        write(
                "{\"seq\":1,\"ts\":1,\"event\":\"user.message\",\"agentId\":\"a_main1\",\"payload\":{\"content\":\"你好\"}}",
                "{\"seq\":2,\"ts\":2,\"event\":\"message\",\"agentId\":\"a_main1\",\"payload\":{\"content\":\"回答\",\"data\":{\"thinking\":\"想了一下\"}}}",
                "{\"seq\":3,\"ts\":3,\"event\":\"done\",\"agentId\":\"a_main1\",\"payload\":{\"summary\":\"回答\"}}");
        List<Message> out = ConversationLoader.load(store, dir, MAIN);
        assertEquals(2, out.size());
        assertEquals("你好", out.get(0).getText(), "user.message → UserMessage");
        AssistantMessage am = (AssistantMessage) out.get(1);
        assertEquals("回答", am.getText());
        assertNull(am.getMetadata().get("reasoningContent"), "thinking 不进 conversation(不回传 API)");
        assertTrue(am.getToolCalls() == null || am.getToolCalls().isEmpty());
    }

    @Test
    void toolRoundsPairedByRealCallId() throws Exception {
        write(
                "{\"seq\":1,\"ts\":1,\"event\":\"user.message\",\"agentId\":\"a_main1\",\"payload\":{\"content\":\"查一下\"}}",
                "{\"seq\":2,\"ts\":2,\"event\":\"message\",\"agentId\":\"a_main1\",\"payload\":{\"content\":\"\",\"data\":{\"toolCalls\":[{\"id\":\"call-1\",\"name\":\"fs.read\",\"arguments\":\"{}\"},{\"id\":\"call-2\",\"name\":\"git.status\",\"arguments\":\"{}\"}]}}}",
                "{\"seq\":3,\"ts\":3,\"event\":\"tool.result\",\"agentId\":\"a_main1\",\"payload\":{\"summary\":\"文件内容\",\"data\":{\"callId\":\"call-1\",\"name\":\"fs.read\",\"status\":\"success\"}}}",
                "{\"seq\":4,\"ts\":4,\"event\":\"tool.result\",\"agentId\":\"a_main1\",\"payload\":{\"summary\":\"干净\",\"data\":{\"callId\":\"call-2\",\"name\":\"git.status\",\"status\":\"success\"}}}",
                "{\"seq\":5,\"ts\":5,\"event\":\"message\",\"agentId\":\"a_main1\",\"payload\":{\"content\":\"汇总\"}}");
        List<Message> out = ConversationLoader.load(store, dir, MAIN);
        assertEquals(4, out.size(), "user + assistant(带calls) + toolResult + assistant(终答)");
        AssistantMessage callMsg = (AssistantMessage) out.get(1);
        assertEquals(2, callMsg.getToolCalls().size());
        assertEquals("call-1", callMsg.getToolCalls().get(0).id(), "真实 toolCall id 保留");
        ToolResponseMessage trm = (ToolResponseMessage) out.get(2);
        assertEquals(2, trm.getResponses().size());
        assertEquals("call-1", trm.getResponses().get(0).id(), "结果按 callId 配对");
        assertEquals("fs.read", trm.getResponses().get(0).name(), "name 供模型端工具映射");
        assertEquals("文件内容", trm.getResponses().get(0).responseData());
    }

    @Test
    void interruptedRoundGetsSyntheticResult() throws Exception {
        write(
                "{\"seq\":1,\"ts\":1,\"event\":\"user.message\",\"agentId\":\"a_main1\",\"payload\":{\"content\":\"做到一半\"}}",
                "{\"seq\":2,\"ts\":2,\"event\":\"message\",\"agentId\":\"a_main1\",\"payload\":{\"content\":\"\",\"data\":{\"toolCalls\":[{\"id\":\"call-x\",\"name\":\"run_agent\",\"arguments\":\"{}\"}]}}}",
                "{\"seq\":3,\"ts\":3,\"event\":\"error\",\"agentId\":\"a_main1\",\"payload\":{\"message\":\"中断\"}}");
        List<Message> out = ConversationLoader.load(store, dir, MAIN);
        assertEquals(3, out.size());
        ToolResponseMessage trm = (ToolResponseMessage) out.get(2);
        assertEquals(1, trm.getResponses().size());
        assertEquals("call-x", trm.getResponses().get(0).id());
        assertEquals("[上一轮被中断,无结果]", trm.getResponses().get(0).responseData());
    }

    @Test
    void multiTurnOrderingAndTransientIgnored() throws Exception {
        // 瞬态事件占 seq:文件里只有持久事件,seq 有洞
        write(
                "{\"seq\":1,\"ts\":1,\"event\":\"user.message\",\"agentId\":\"a_main1\",\"payload\":{\"content\":\"第一问\"}}",
                "{\"seq\":4,\"ts\":4,\"event\":\"message\",\"agentId\":\"a_main1\",\"payload\":{\"content\":\"第一答\"}}",
                "{\"seq\":5,\"ts\":5,\"event\":\"user.message\",\"agentId\":\"a_main1\",\"payload\":{\"content\":\"第二问\"}}",
                "{\"seq\":8,\"ts\":8,\"event\":\"message\",\"agentId\":\"a_main1\",\"payload\":{\"content\":\"第二答\"}}");
        List<Message> out = ConversationLoader.load(store, dir, MAIN);
        assertEquals(4, out.size());
        assertEquals("第一问", out.get(0).getText());
        assertEquals("第一答", out.get(1).getText());
        assertEquals("第二问", out.get(2).getText());
        assertEquals("第二答", out.get(3).getText());
    }

    @Test
    void legacyAndMissingReturnEmpty() {
        assertTrue(ConversationLoader.load(store, dir, "").isEmpty(), "旧格式无 mainAgentId");
        assertTrue(ConversationLoader.load(store, dir, null).isEmpty());
        assertTrue(ConversationLoader.load(store, dir, "a_nobody").isEmpty(), "文件缺失返回空");
    }

    /**
     * 角色中性重建:子 agent 的 &lt;subId&gt;.jsonl(agent.input 指令轮 + message +
     * tool.result)与主 agent 走同一谓词同一入口——跨轮/跨重启续跑子 agent 的依据。
     */
    @Test
    void subAgentArchiveRebuildsByAgentId() throws Exception {
        String sub = "sub-abc";
        Files.writeString(dir.resolve(sub + ".jsonl"), String.join("\n",
                "{\"seq\":10,\"ts\":1,\"event\":\"agent.input\",\"agentId\":\"sub-abc\",\"payload\":{\"content\":\"调研 X 库的用法\"}}",
                "{\"seq\":11,\"ts\":2,\"event\":\"message\",\"agentId\":\"sub-abc\",\"payload\":{\"content\":\"\",\"data\":{\"toolCalls\":[{\"id\":\"c1\",\"name\":\"read_file\",\"arguments\":\"{}\"}]}}}",
                "{\"seq\":12,\"ts\":3,\"event\":\"tool.result\",\"agentId\":\"sub-abc\",\"payload\":{\"summary\":\"文件内容\",\"data\":{\"callId\":\"c1\"}}}",
                "{\"seq\":13,\"ts\":4,\"event\":\"message\",\"agentId\":\"sub-abc\",\"payload\":{\"content\":\"X 库结论\"}}",
                "{\"seq\":14,\"ts\":5,\"event\":\"agent.input\",\"agentId\":\"sub-abc\",\"payload\":{\"content\":\"补充:对比 Y 库\"}}",
                "{\"seq\":15,\"ts\":6,\"event\":\"message\",\"agentId\":\"sub-abc\",\"payload\":{\"content\":\"对比结论\"}}") + "\n");
        List<Message> out = ConversationLoader.load(store, dir, sub);
        assertEquals(6, out.size(), "user/assistant 两轮完整配对 + 工具轮");
        assertEquals("调研 X 库的用法", out.get(0).getText(), "agent.input → UserMessage(与 user.message 同谓词)");
        AssistantMessage callMsg = (AssistantMessage) out.get(1);
        assertEquals(1, callMsg.getToolCalls().size(), "工具调用轮按真实 id 保留");
        assertEquals("文件内容", ((ToolResponseMessage) out.get(2)).getResponses().get(0).responseData());
        assertEquals("X 库结论", out.get(3).getText());
        assertEquals("补充:对比 Y 库", out.get(4).getText(), "续跑指令(agent.input)同谓词折入");
        assertEquals("对比结论", out.get(5).getText());
        for (int i = 1; i < out.size(); i++) {
            assertTrue(!(out.get(i) instanceof org.springframework.ai.chat.messages.UserMessage
                    && out.get(i - 1) instanceof org.springframework.ai.chat.messages.UserMessage),
                    "重建序列不得出现连续 user(第 " + i + " 项)");
        }
    }
}
