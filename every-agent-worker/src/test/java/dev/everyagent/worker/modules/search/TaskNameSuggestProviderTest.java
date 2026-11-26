package dev.everyagent.worker.modules.search;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.plugin.api.spi.SuggestionProvider;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.task.TaskStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置「任务名」@ 建议源：标题包含/词首/子序列命中、大小写不敏感、上限 5、
 * 空参返回空、标题缺省回退。数据经 TaskStore 磁盘扫描（meta.json 即可）。
 */
class TaskNameSuggestProviderTest {

    @TempDir
    Path tempDir;

    private WorkerProperties props;
    private TaskNameSuggestProvider provider;

    private static final String WS = "defaultworkspace";

    @BeforeEach
    void setUp() {
        props = new WorkerProperties();
        props.setHomeDir(tempDir.resolve("home").toString());
        provider = new TaskNameSuggestProvider(new TaskStore(props));
    }

    private void seedTask(String taskId, String title) throws Exception {
        Path dir = props.resolveWorkspacesDir().resolve(WS).resolve("tasks").resolve(taskId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("meta.json"),
                "{\"taskId\":\"" + taskId + "\",\"title\":\"" + title
                        + "\",\"status\":\"done\",\"workspaceId\":\"" + WS + "\"}");
    }

    private SuggestionProvider.SuggestRequest req(String query) {
        return new SuggestionProvider.SuggestRequest(WS, Path.of("."), query, ".");
    }

    @Test
    void containsHitCarriesTaskIdAndTitleInMetadata() throws Exception {
        seedTask("t1", "部署发版流程");
        seedTask("t2", "写作流程");

        var list = provider.suggest(req("发版"));

        assertEquals(1, list.size());
        var s = list.get(0);
        assertEquals("tasks/t1", s.path());
        assertEquals(TaskNameSuggestProvider.KIND, s.kind());
        assertEquals("t1", String.valueOf(s.metadata().get("taskId")));
        assertEquals("部署发版流程", String.valueOf(s.metadata().get("title")));
    }

    @Test
    void caseInsensitiveEnglishTitle() throws Exception {
        seedTask("t3", "Deploy Step Guide");
        assertEquals(1, provider.suggest(req("deploy step")).size());
    }

    @Test
    void subsequenceHitFallsBackToQualityOne() throws Exception {
        seedTask("t4", "周报生成器");
        var list = provider.suggest(req("周生"));
        assertEquals(1, list.size());
        assertEquals("tasks/t4", list.get(0).path());
    }

    @Test
    void cappedAtFiveSuggestions() throws Exception {
        for (int i = 0; i < 8; i++) {
            seedTask("k" + i, "关键词任务" + i);
        }
        assertEquals(5, provider.suggest(req("关键词")).size());
    }

    @Test
    void blankQueryOrWorkspaceReturnsEmpty() throws Exception {
        seedTask("t5", "部署流程");
        assertTrue(provider.suggest(req("")).isEmpty());
        assertTrue(provider.suggest(
                new SuggestionProvider.SuggestRequest(null, Path.of("."), "部署", ".")).isEmpty());
    }

    @Test
    void missingTitleFallsBackToTaskId() throws Exception {
        Path dir = props.resolveWorkspacesDir().resolve(WS).resolve("tasks").resolve("t6");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("meta.json"), "{\"taskId\":\"t6\"}");
        var list = provider.suggest(req("t6"));
        assertEquals(1, list.size());
        assertEquals("任务 t6", String.valueOf(list.get(0).metadata().get("title")));
    }
}
