package dev.everyagent.hub.ws;

import dev.everyagent.contract.ids.Ids;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * hub 去业务化后的频道规则:没有角色矩阵,唯一规则是命名空间边界 + 字符集/长度。
 * 同命名空间(同一 apiKey)内任意角色可 sub/pub 任意频道——持有 apiKey 即命名空间全权;
 * 业务归属校验(如任务是不是你的)由 worker 完成,hub 不理解任何频道语义。
 */
class ChannelRulesTest {

    private final String k = "f".repeat(64);
    private final String prefix = "u." + k + ".";

    @Test
    void channelValidation() {
        assertNull(ChannelRules.channelError(prefix + "task.abc.stream", k));
        assertNull(ChannelRules.channelError(prefix + "workers", k));
        assertNull(ChannelRules.channelError(prefix + "a", k));

        assertNotNull(ChannelRules.channelError(null, k));
        assertNotNull(ChannelRules.channelError("", k));
        assertNotNull(ChannelRules.channelError("u." + "e".repeat(64) + ".x", k), "跨命名空间");
        assertNotNull(ChannelRules.channelError(prefix + "Bad", k), "大写非法");
        assertNotNull(ChannelRules.channelError(prefix + "x#y", k), "非法字符");
        assertNotNull(ChannelRules.channelError(prefix + "x".repeat(161), k), "超长");
        assertNotNull(ChannelRules.channelError(prefix, k), "空前缀后缀");
        assertNotNull(ChannelRules.channelError("u." + k.substring(0, 63) + ".z", k), "ownerKey 截断不算前缀");
    }

    @Test
    void namespaceIsTheOnlyBoundary() {
        // hub 不再区分角色:cmd/evt/stream/input/workers/tasks 与自建频道一视同仁,
        // 只要落在自己命名空间内即可(业务互信由 worker 校验)。
        assertNull(ChannelRules.channelError(prefix + "worker.w1.cmd", k));
        assertNull(ChannelRules.channelError(prefix + "worker.w1.evt", k));
        assertNull(ChannelRules.channelError(prefix + "worker.w1.input", k));
        assertNull(ChannelRules.channelError(prefix + "task.t1.stream", k));
        assertNull(ChannelRules.channelError(prefix + "tasks", k));
        assertNull(ChannelRules.channelError(prefix + "room.1", k));

        // 他人命名空间一律拒绝——与角色无关
        String other = "u." + "e".repeat(64) + ".";
        assertNotNull(ChannelRules.channelError(other + "worker.w1.cmd", k));
        assertNotNull(ChannelRules.channelError(other + "room.1", k));
        // 无命名空间前缀的自建频道也拒绝(身份必须显式)
        assertNotNull(ChannelRules.channelError("room.1", k));
    }

    @Test
    void ownerKeyIsSha256Hex() {
        String key = Ids.ownerKey("sk-test");
        assertEquals(64, key.length());
        assertEquals(key, key.toLowerCase());
    }
}
