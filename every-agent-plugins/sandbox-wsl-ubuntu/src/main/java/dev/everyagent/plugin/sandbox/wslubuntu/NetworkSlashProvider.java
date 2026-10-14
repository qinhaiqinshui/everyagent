package dev.everyagent.plugin.sandbox.wslubuntu;

import java.util.List;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.plugin.api.slash.SlashSelectHandler;
import dev.everyagent.plugin.api.slash.SlashSelectionResult;

/**
 * 「/禁用网络」命令来源 —— <b>只有 wsl-ubuntu 后端做得到真断网</b>,故这条命令与它的状态
 * 整体归本插件(仿 {@code unattended.UnattendedSlashProvider}):用户选择后把「禁用网络」
 * 胶囊(bottom 底部渲染)挂到本任务,由 {@link WslUbuntuCommandExecutor} 在发行版内以
 * {@code unshare -n} 落地。
 *
 * <p>开关状态的<b>唯一真相源</b>是任务级 {@code network.access} slash token(落盘于任务目录
 * 下的 {@code slash-tokens.json},由 slash 层在 slash.select / slash.cancel 时写入 / 移除):
 * 选中即写入 token(开启),✕ 取消即移除(关闭)。本 provider 的两条回调只决定「往哪插、
 * 插什么」,不写任何任务 {@code metadata} 副本 —— 读取统一走 {@link NetworkTaskFlag#isOn},
 * 恒以磁盘为准,运行中 / 终态 / 进程重启后一致。
 *
 * <p>选中即开启<b>任务级</b>禁网开关 —— 本次任务后续所有轮次(含再运行)持续生效,且运行中
 * 选中/取消<b>对本轮后续命令即时生效</b>(每次 spawn 实时读);胶囊底部渲染,可随时 ✕ 取消。
 * worker 级默认 {@code sandbox.allow-network=true}(放行网络),本开关只对单任务收窄。
 *
 * <p>{@code /} 菜单条目按当前生效后端过滤:非 wsl-ubuntu(direct / windows-mic 只能剥代理
 * env、拦不住直连)不提供该命令,避免出现「选了却不生效」的开关。
 */
public final class NetworkSlashProvider {

    /** 本命令唯一生效的沙箱后端 id(其他后端做不到硬断网,不挂该命令)。 */
    static final String BACKEND_ID = "wsl-ubuntu";

    /** slash 条目 id(前端胶囊归属与 slash.select/cancel 反查用)。 */
    static final String ITEM_ID = "network:on";

    /** `/` 菜单里网络候选的分组名(直接作为展示标题)。 */
    private static final String NETWORK_GROUP = "禁用网络";

    /** 候选在 `/` 菜单里的图标(内联 SVG,currentColor 上色:地球,表「网络」)。 */
    private static final String NETWORK_MENU_ICON =
            "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\""
                    + " stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">"
                    + "<circle cx=\"8\" cy=\"8\" r=\"6\"/><path d=\"M2 8h12M8 2c2 2 3 4 3 6s-1 4-3 6c-2-2-3-4-3-6s1-4 3-6Z\"/></svg>";

    private NetworkSlashProvider() {
    }

    /**
     * 返回 `/禁用网络` 候选项(选中即构造自包含 opaque 串 → 底部渲染胶囊,可取消);
     * 当前生效后端不是 wsl-ubuntu 时返回空列表(该后端做不到,不提供该命令)。
     */
    public static List<SlashCommandItem> items(WorkerServices services) {
        if (services == null || services.sandbox() == null
                || !BACKEND_ID.equals(services.sandbox().id())) {
            return List.of();
        }
        String subtitle = "禁止本任务访问网络（默认放行，本任务有效，底部可取消）";
        // 业务 onSelect:开关的真相源是任务级 network.access token(slash.select RPC 负责落入
        // slash-tokens.json);本回调只返回 bottom 展示结果,不写输入框、也不写任何任务 metadata。
        SlashSelectHandler selectHandler = (item, taskId) ->
                List.of(SlashSelectionResult.bottom(NetworkToken.buildToken(), ITEM_ID));
        // 业务 onCancel:取消即移除任务级 network.access token(真相源),由 slash.cancel RPC
        // 落地;本插件无须在此回写任何状态,故不挂回调(构造传 null → 默认 NOOP 空操作)。
        return List.of(new SlashCommandItem(
                ITEM_ID,
                "禁用网络",
                subtitle,
                NETWORK_MENU_ICON,
                NETWORK_GROUP,
                NetworkToken.buildToken(),
                selectHandler,
                null));
    }
}
