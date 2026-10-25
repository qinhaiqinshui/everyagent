package dev.everyagent.plugin.taskwait;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WaitTaskToken 单测：build/parse 往返；scanWaits 多 token / 异 kind / 坏串容错。
 */
class WaitTaskTokenTest {

    // ── build / parse 往返 ──────────────────────────────────────

    @Test
    void buildTokenRoundTrip() {
        String token = WaitTaskToken.buildToken("t-100", "写周报");

        SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(token);
        assertNotNull(parsed, "buildToken 产物必须可解析");
        assertEquals(WaitTaskToken.KIND, parsed.kind(), "kind=task.wait");
        assertEquals("等待「写周报」", parsed.label(), "胶囊 label=等待「任务名」");
        assertEquals("等待该任务完成后继续（本次输入有效）", parsed.summary());
        assertEquals("t-100", parsed.payload().path("targetTaskId").asString());
        assertEquals("写周报", parsed.payload().path("title").asString());
    }

    @Test
    void tokenCarriesOpaqueDelimiters() {
        String token = WaitTaskToken.buildToken("t-1", "A");
        assertTrue(token.startsWith(SlashTokenEncoder.OPAQUE_PREFIX), "opaque 前缀 [[[[");
        assertTrue(token.endsWith(SlashTokenEncoder.OPAQUE_SUFFIX), "opaque 后缀 ]]]]");
    }

    // ── scanWaits ───────────────────────────────────────────────

    @Test
    void scanFindsAllTokensInOrder() {
        String raw = "开头 " + WaitTaskToken.buildToken("t-1", "任务一")
                + " 中间正文 " + WaitTaskToken.buildToken("t-2", "任务二") + " 结尾";
        List<WaitTaskToken.WaitRef> refs = WaitTaskToken.scanWaits(raw);

        assertEquals(2, refs.size(), "两枚胶囊都命中");
        assertEquals("t-1", refs.get(0).targetTaskId());
        assertEquals("任务一", refs.get(0).title());
        assertEquals("t-2", refs.get(1).targetTaskId());
        assertEquals("任务二", refs.get(1).title());
    }

    @Test
    void scanSkipsOtherKinds() {
        String other = SlashTokenEncoder.buildToken("other.kind", "别的胶囊", null,
                Json.obj().put("targetTaskId", "t-x"));
        String raw = other + " " + WaitTaskToken.buildToken("t-1", "任务一");

        List<WaitTaskToken.WaitRef> refs = WaitTaskToken.scanWaits(raw);

        assertEquals(1, refs.size(), "异 kind 段跳过");
        assertEquals("t-1", refs.get(0).targetTaskId());
    }

    @Test
    void scanSkipsWaitKindWithoutTargetTaskId() {
        // kind 对但 payload 缺 targetTaskId：构造合法 token、payload 只有 title
        String malformed = SlashTokenEncoder.buildToken(WaitTaskToken.KIND, "等待「X」", null,
                Json.obj().put("title", "X"));
        List<WaitTaskToken.WaitRef> refs = WaitTaskToken.scanWaits(malformed);
        assertTrue(refs.isEmpty(), "缺 targetTaskId 的段不产出 WaitRef");
    }

    @Test
    void scanToleratesBrokenSegments() {
        // 三段：非 agent-token 格式 / 异 kind / 合法等待胶囊 → 只有第三段命中
        String broken = "[[[[garbage]]]]"
                + "[[[[agent-token::::other.kind||||label====x]]]]"
                + " 尾部 " + WaitTaskToken.buildToken("t-9", "任务九");
        List<WaitTaskToken.WaitRef> refs = WaitTaskToken.scanWaits(broken);

        assertEquals(1, refs.size(), "坏段/异 kind 跳过，合法段照常解析");
        assertEquals("t-9", refs.get(0).targetTaskId());
    }

    @Test
    void scanStopsAtUnclosedSegment() {
        // 截断段（无 ]]]] 闭合）：后续扫描终止，返回空
        String truncated = "[[[[agent-token::::task.wait||||label====等待「X」 无闭合";
        assertTrue(WaitTaskToken.scanWaits(truncated).isEmpty());
    }

    @Test
    void scanSkipsSegmentWithCorruptPayload() {
        // payload base64 非法：parseToken 兜底空 payload → 缺 targetTaskId → 跳过
        String corrupt = "[[[[agent-token::::task.wait||||label====等待「X」||||payload====!!!!]]]]"
                + WaitTaskToken.buildToken("t-9", "任务九");
        List<WaitTaskToken.WaitRef> refs = WaitTaskToken.scanWaits(corrupt);

        assertEquals(1, refs.size(), "payload 损坏的段跳过，不抛异常");
        assertEquals("t-9", refs.get(0).targetTaskId());
    }

    @Test
    void scanReturnsEmptyOnNullEmptyOrPlainContent() {
        assertTrue(WaitTaskToken.scanWaits(null).isEmpty());
        assertTrue(WaitTaskToken.scanWaits("").isEmpty());
        assertTrue(WaitTaskToken.scanWaits("普通文本，无任何胶囊").isEmpty());
    }
}
