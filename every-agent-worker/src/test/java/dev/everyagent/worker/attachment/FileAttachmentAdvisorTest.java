package dev.everyagent.worker.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;

import dev.everyagent.plugin.api.execution.ExecContext;

/**
 * {@link FileAttachmentAdvisor} 附件注入五条钉子：前两条守住现状（无附件 / 无媒体时注入），
 * 后三条钉「已带媒体」语义——合并去重（同一 run 中途 read_file 接管可追加附件，
 * 「跳过守卫」会让后进图片本轮不可见）：
 * <ol>
 *   <li>metadata 无附件 → 原样放行（请求实例同一）；</li>
 *   <li>metadata 有图片附件 + 末位 user 无媒体 → 注入（现状行为不回归）；</li>
 *   <li>末位 user 已带媒体 A + attachments=[A,B] → 重建消息，media=[A,B]
 *       （既有在前、新增按候选序，B 即时可见）；</li>
 *   <li>末位 user 已带媒体 A + attachments=[A] → 原样放行（不重建，消息实例同一）；</li>
 *   <li>无 user 消息（纯 system）→ 原样放行。</li>
 * </ol>
 *
 * <p>判重口径按 data+name+mimeType（{@link Media} 未覆写 equals），既有媒体经
 * {@link AttachmentMedia#toMedia} 用同一份附件 map 生成，保证与候选逐字段相同。
 */
class FileAttachmentAdvisorTest {

    private static final String DATA_URL_A = "data:image/png;base64,QUFBQQ==";
    private static final String DATA_URL_B = "data:image/png;base64,QkJCQg==";

    // ---- 帮助方法 ----

    /** 可变 metadata（含 attachments），供 advisor 读取。 */
    private static Map<String, Object> metadataWith(List<Map<String, Object>> attachments) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ExecContext.METADATA_ATTACHMENTS_KEY, attachments);
        return metadata;
    }

    private static Map<String, Object> imageAttachment(String fileName, String dataUrl) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "image");
        map.put("dataUrl", dataUrl);
        map.put("fileName", fileName);
        map.put("mimeType", "image/png");
        return map;
    }

    private static ExecContext exec(Map<String, Object> metadata) {
        ExecContext exec = mock(ExecContext.class);
        when(exec.metadata()).thenReturn(metadata);
        return exec;
    }

    /** 与 SkillAdvisorTest 同款请求构造：system + user（末位 user 可带既有 media）。 */
    private static ChatClientRequest request(UserMessage lastUser) {
        List<Message> instructions = new ArrayList<>();
        instructions.add(new SystemMessage("你是助手"));
        instructions.add(lastUser);
        return ChatClientRequest.builder()
                .prompt(new Prompt(instructions, mock(ChatOptions.class)))
                .build();
    }

    private static UserMessage userWithMedia(String text, List<Media> media) {
        return UserMessage.builder().text(text).media(media).build();
    }

    private static UserMessage call(FileAttachmentAdvisor advisor, UserMessage lastUser) {
        ChatClientRequest out = advisor.before(request(lastUser), mock(AdvisorChain.class));
        return lastUser(out);
    }

    private static UserMessage lastUser(ChatClientRequest request) {
        List<Message> instructions = request.prompt().getInstructions();
        return (UserMessage) instructions.get(instructions.size() - 1);
    }

    /** 按 dataUrl 找 Media（断言「某附件在场」的口径，与判重口径一致）。 */
    private static Media mediaByDataUrl(UserMessage message, String dataUrl) {
        for (Media m : message.getMedia()) {
            if (dataUrl.equals(m.getData())) {
                return m;
            }
        }
        return null;
    }

    // ---- 钉子 1：无附件 → 原样放行 ----

    @Test
    void noAttachmentsPassesThrough() {
        FileAttachmentAdvisor advisor = new FileAttachmentAdvisor(exec(metadataWith(List.of())));
        ChatClientRequest request = request(new UserMessage("你好"));
        assertSame(request, advisor.before(request, mock(AdvisorChain.class)),
                "无附件应原样放行（同一请求实例）");
    }

    // ---- 钉子 2：有附件 + 末位 user 无媒体 → 注入（现状不回归） ----

    @Test
    void injectsIntoLastUserWithoutMedia() {
        Map<String, Object> attachment = imageAttachment("a.png", DATA_URL_A);
        FileAttachmentAdvisor advisor = new FileAttachmentAdvisor(
                exec(metadataWith(new ArrayList<>(List.of(attachment)))));

        UserMessage out = call(advisor, new UserMessage("看图"));

        assertNotNull(mediaByDataUrl(out, DATA_URL_A), "末位 user 无媒体时应注入附件");
        assertEquals("看图", out.getText(), "正文保持不变");
    }

    // ---- 钉子 3：已带媒体 A + attachments=[A,B] → 合并去重，B 即时可见 ----

    @Test
    void mergesNewAttachmentWhenMediaAlreadyPresent() {
        Map<String, Object> mapA = imageAttachment("a.png", DATA_URL_A);
        Map<String, Object> mapB = imageAttachment("b.png", DATA_URL_B);
        Map<String, Object> metadata = metadataWith(new ArrayList<>(List.of(mapA, mapB)));
        FileAttachmentAdvisor advisor = new FileAttachmentAdvisor(exec(metadata));

        // 既有媒体 A 与候选 A 逐字段相同（同一转换器产出，仅实例不同——Media 无 equals）
        Media existingA = AttachmentMedia.toMedia(List.of(mapA)).get(0);
        UserMessage out = call(advisor, userWithMedia("看图", List.of(existingA)));

        assertEquals(2, out.getMedia().size(), "应合并为 2 个媒体（去重后 A+B）: " + out.getMedia());
        assertSame(existingA, out.getMedia().get(0), "既有媒体保留在前（原实例）");
        assertNotNull(mediaByDataUrl(out, DATA_URL_B), "新增 B 必须即时可见（不被整条跳过）");
        assertEquals("看图", out.getText(), "正文保持不变");
    }

    // ---- 钉子 4：已带媒体 A + attachments=[A] → 原样放行（不重建消息） ----

    @Test
    void passesThroughWhenAllAttachmentsAlreadyPresent() {
        Map<String, Object> mapA = imageAttachment("a.png", DATA_URL_A);
        FileAttachmentAdvisor advisor = new FileAttachmentAdvisor(
                exec(metadataWith(new ArrayList<>(List.of(mapA)))));

        Media existingA = AttachmentMedia.toMedia(List.of(mapA)).get(0);
        UserMessage lastUser = userWithMedia("看图", List.of(existingA));
        ChatClientRequest request = request(lastUser);

        ChatClientRequest out = advisor.before(request, mock(AdvisorChain.class));
        assertSame(request, out, "候选全部已存在时应原样放行（不重建请求）");
        assertSame(lastUser, lastUser(out), "末位 user 消息实例应保持同一（未重建）");
        assertEquals(1, lastUser.getMedia().size(), "媒体数量不变（不重复注入）");
    }

    // ---- 钉子 5：无 user 消息（纯 system）→ 原样放行 ----

    @Test
    void noUserMessagePassesThrough() {
        Map<String, Object> attachment = imageAttachment("a.png", DATA_URL_A);
        FileAttachmentAdvisor advisor = new FileAttachmentAdvisor(
                exec(metadataWith(new ArrayList<>(List.of(attachment)))));

        List<Message> instructions = new ArrayList<>();
        instructions.add(new SystemMessage("你是助手"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(instructions, mock(ChatOptions.class)))
                .build();

        assertSame(request, advisor.before(request, mock(AdvisorChain.class)),
                "无 user 消息应原样放行（同一请求实例）");
    }
}
