package dev.everyagent.worker.git;

import java.util.List;

import org.springframework.stereotype.Component;

import dev.everyagent.worker.slash.SlashCommandItem;
import dev.everyagent.worker.slash.SlashCommandRegistry;

/**
 * 「/自动同步」命令来源(对齐 old {@code plugins/git/gitAutoSyncSlashProvider.ts}):
 * 用户选择后向输入框插入自动同步胶囊(opaque token)。
 *
 * <p>选中即对本轮任务打「完成后自动同步」标记——{@code GitAutoSyncAdvisor} 在任务收口
 * 阶段读取该标记,执行与 Git 面板同步按钮等价的完整同步(检查远端 → 必要时拉取 →
 * 提交本地变更 → 推送)。标记<b>仅本轮有效</b>:下一条消息未再选本命令即恢复默认(不自动同步)。
 *
 * <p>注册路径:本 provider 经构造器向 {@link SlashCommandRegistry} 登记(同
 * {@code SkillSlashProvider}),{@code slash.list} 聚合时统一下发;文件名无约束
 * (Registry 是热插拔扩展的唯一承接基座)。
 */
@Component
public class GitAutoSyncSlashProvider {

    /** `/` 菜单里自动同步候选的分组名(直接作为展示标题)。 */
    private static final String GIT_AUTO_SYNC_GROUP = "Git";

    /** 候选在 `/` 菜单里的图标(内联 SVG,currentColor 上色,照搬 old 的勾选图标)。 */
    private static final String GIT_AUTO_SYNC_MENU_ICON =
            "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\""
                    + " stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">"
                    + "<path d=\"M3.5 8.5 6.5 11.5 12.5 4.5\" /></svg>";

    public GitAutoSyncSlashProvider(SlashCommandRegistry registry) {
        registry.registerProvider("git-auto-sync", GitAutoSyncSlashProvider::items);
    }

    /** 返回 `/自动同步` 候选项(选中即构造自包含 opaque 串 → 显示胶囊)。 */
    private static List<SlashCommandItem> items() {
        return List.of(new SlashCommandItem(
                "git-auto-sync:on",
                "自动同步",
                "本轮任务完成后自动 git 提交并推送到远端（同 Git 面板同步按钮，仅本轮有效）",
                GIT_AUTO_SYNC_MENU_ICON,
                GIT_AUTO_SYNC_GROUP,
                GitAutoSyncToken.buildToken(true)));
    }
}
