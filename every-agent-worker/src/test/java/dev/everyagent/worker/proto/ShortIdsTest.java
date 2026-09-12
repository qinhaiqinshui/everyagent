package dev.everyagent.worker.proto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * ShortIds 盐长度回归:盐从 2 位升到 3 位是为了显著降低 worker 重启后
 * 「同盐 + 计数器复位」撞出旧 taskId 的概率(曾实测 t_ct1/t_ct2 复用旧目录事故)。
 * 该测试锁定 3 位盐格式,防止误改回 2 位。
 */
class ShortIdsTest {

    @Test
    void taskId_usesThreeCharSalt() {
        String id = ShortIds.taskId();
        // 形如 t_k3f1:前缀 + '_' + 3 位盐 + base36 序号(>=1 位)
        assertTrue(id.matches("t_[0-9a-z]{3}[0-9a-z]+"), "taskId 盐应为 3 位: " + id);
    }

    @Test
    void ids_incrementWithinProcess() {
        String first = ShortIds.taskId();
        String second = ShortIds.taskId();
        assertNotEquals(first, second, "同进程内 taskId 应自增不复用");
    }

    @Test
    void prefixes_areStable() {
        assertTrue(ShortIds.mainAgentId().startsWith("a_"));
        assertTrue(ShortIds.subAgentId().startsWith("sub_"));
        assertTrue(ShortIds.askId().startsWith("q_"));
        assertEquals("m", ShortIds.mid().substring(0, 1));
    }
}
