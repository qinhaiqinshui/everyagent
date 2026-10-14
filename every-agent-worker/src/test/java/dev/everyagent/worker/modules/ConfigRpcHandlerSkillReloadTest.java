package dev.everyagent.worker.modules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.skill.BuiltInSkills;
import dev.everyagent.worker.skill.ExternalSkillScanner;

/**
 * {@code skill.reload} 的动作测试:内置知识包复原 + 外部 skill 重扫。
 *
 * <p>直接调 {@link ConfigRpcHandler#reloadSkills()}(RPC 壳只多一层 ctx 应答),
 * 其余协作者用 mock 占位——reloadSkills 只碰 BuiltInSkills 与 ExternalSkillScanner。
 */
class ConfigRpcHandlerSkillReloadTest {

    @Test
    void reloadRestoresBuiltInPackAndRescansExternal(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        WorkerProperties props = new WorkerProperties();
        props.setSkillsDir(skillsDir.toString());

        BuiltInSkills builtInSkills = new BuiltInSkills(props);
        builtInSkills.materialize();
        Path planMd = skillsDir.resolve("plan").resolve("skill.md");
        byte[] workerVersion = Files.readAllBytes(planMd);

        ExternalSkillScanner scanner = new ExternalSkillScanner(builtInSkills);

        // 用户/AI 把内置知识包改坏(此处改为等长之外的任意内容)
        Files.writeString(planMd, "被改坏的内置知识包\n");
        // 再放一个外部 skill
        Path external = Files.createDirectory(skillsDir.resolve("code-review"));
        Files.writeString(external.resolve("skill.md"), "# Code Review\n\n审查代码变更。\n");

        ConfigRpcHandler handler = new ConfigRpcHandler(
                mock(ConfigStore.class),
                scanner,
                builtInSkills,
                mock(UserPreferenceStore.class),
                mock(HubPool.class),
                mock(RpcDispatcher.class));

        int count = handler.reloadSkills();

        assertEquals(1, count, "应扫到 1 个外部 skill(code-review);内置 id 被排除");
        assertArrayEquals(workerVersion, Files.readAllBytes(planMd),
                "skill.reload 应把被改坏的内置知识包复原为 worker 版本");
    }
}