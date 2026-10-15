package dev.everyagent.worker.slash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.JsonNode;

/**
 * SlashTaskScopeStore 只读查询测试:{@code tokensOf} / {@code hasKind} 恒以磁盘
 * {@code slash-tokens.json} 为唯一真相源,token 存在即开关开启。
 *
 * <p>数据目录由 Mockito mock 的 {@link WorkerServices#dataDirOf(String)} 提供(打到
 * {@code @TempDir});token 用 {@link SlashTokenEncoder#buildToken} 真实构造,直接落盘
 * 一个 JSON 字符串数组,不经过 apply(测试只关心读路径)。
 */
class SlashTaskScopeStoreTest {

    private static final String TASK = "t-1";

    @TempDir
    Path tmp;

    /** 构造 token 存储:dataDirOf(TASK) 指向临时目录。 */
    private SlashTaskScopeStore store() {
        WorkerServices services = mock(WorkerServices.class);
        when(services.dataDirOf(TASK)).thenReturn(tmp);
        return new SlashTaskScopeStore(services);
    }

    /** 构造一个 kind 非空、payload 为 {"enabled":true} 的合法 opaque token。 */
    private static String token(String kind, String label) {
        JsonNode payload = Json.obj().put("enabled", true);
        return SlashTokenEncoder.buildToken(kind, label, null, payload);
    }

    /** 把若干字符串原样写成 JSON 字符串数组到 <dataDir>/slash-tokens.json(LF/UTF-8)。 */
    private void writeTokensFile(String... rawTokens) {
        ArrayNode arr = Json.arr();
        for (String t : rawTokens) {
            arr.add(t);
        }
        try {
            Files.writeString(tmp.resolve("slash-tokens.json"), Json.write(arr),
                    StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- 用例 1:hasKind 命中 ----

    @Test
    void hasKindMatchesWrittenKind() {
        writeTokensFile(token("ai.review", "AI 审议"));
        SlashTaskScopeStore store = store();

        assertTrue(store.hasKind(TASK, "ai.review"), "已写入的 kind 应命中");
        assertFalse(store.hasKind(TASK, "unattended.mode"), "未写入的 kind 不应命中");
    }

    // ---- 用例 2:未命中/空 ----

    @Test
    void noFileYieldsAllFalse() {
        SlashTaskScopeStore store = store(); // 不写文件

        assertFalse(store.hasKind(TASK, "ai.review"));
        assertFalse(store.hasKind(TASK, null));
        assertFalse(store.hasKind(TASK, ""));
        assertTrue(store.tokensOf(TASK).isEmpty(), "文件不存在视为空列表");
    }

    // ---- 用例 3:非法 token 混入不抛异常 ----

    @Test
    void invalidTokenIsSkipped() {
        writeTokensFile("not-a-token");
        SlashTaskScopeStore store = store();

        assertFalse(store.hasKind(TASK, "ai.review"), "垃圾 token 跳过,不命中");
    }

    // ---- 用例 4:tokensOf 与 hasKind 一致 ----

    @Test
    void tokensOfAndHasKindStayConsistent() {
        writeTokensFile(token("ai.review", "AI 审议"), token("unattended.mode", "无人值守"));
        SlashTaskScopeStore store = store();

        List<String> tokens = store.tokensOf(TASK);
        assertEquals(2, tokens.size(), "两条 token 都应读出");
        assertTrue(store.hasKind(TASK, "ai.review"));
        assertTrue(store.hasKind(TASK, "unattended.mode"));
        assertFalse(store.hasKind(TASK, "network.disabled"));
    }
}