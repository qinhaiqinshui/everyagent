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
import dev.everyagent.worker.skill.BuiltInSkillPacks;
import dev.everyagent.worker.skill.SkillCatalog;

/**
 * {@code skill.reload} 的动作测试:内置技能包复原 + 系统技能目录重扫。
 *
 * <p>直接调 {@link ConfigRpcHandler#reloadSkills()}(RPC 壳只多一层 ctx 应答),
 * 其余协作者用 mock 占位——reloadSkills 只碰 BuiltInSkillPacks 与 SkillCatalog。
 */
class ConfigRpcHandlerSkillReloadTest {

    @Test
    void reloadRestoresBuiltInPackAndRescansCatalog(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        WorkerProperties props = new WorkerProperties();
        props.setSkillsDir(skillsDir.toString());

        BuiltInSkillPacks packs = new BuiltInSkillPacks(props);
        packs.materialize();
        Path planMd = skillsDir.resolve("plan").resolve("skill.md");
        byte[] workerVersion = Files.readAllBytes(planMd);

        SkillCatalog catalog = new SkillCatalog(props);

        // 用户/AI 把内置知识包改坏
        Files.writeString(planMd, "被改坏的内置知识包\n");
        // 再放一个用户自制外部 skill
        Path external = Files.createDirectory(skillsDir.resolve("code-review"));
        Files.writeString(external.resolve("skill.md"), "# Code Review\n\n审查代码变更。\n");

        ConfigRpcHandler handler = new ConfigRpcHandler(
                mock(ConfigStore.class),
                catalog,
                packs,
                mock(SkillExclusionStore.class),
                mock(WorkspaceManager.class),
                mock(UserPreferenceStore.class),
                mock(HubPool.class),
                mock(RpcDispatcher.class));

        int count = handler.reloadSkills();

        assertEquals(3, count, "目录即真相源:内置 plan/skill-creator + 外部 code-review 共 3 个");
        assertArrayEquals(workerVersion, Files.readAllBytes(planMd),
                "skill.reload 应把被改坏的内置知识包复原为 worker 版本");
    }
}
