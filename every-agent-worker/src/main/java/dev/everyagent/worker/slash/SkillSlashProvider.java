package dev.everyagent.worker.slash;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import dev.everyagent.worker.skill.Skill;
import dev.everyagent.worker.skill.SkillCatalog;

/**
 * 把 skill 注册进 `/` 菜单(老项目 {@code skillSlashProvider} 的 worker 侧等价物)。
 *
 * <p>每个 skill → 一条 `/` 候选项,{@code insertText} 为自包含 opaque token
 * (kind={@code system.skill}),payload 与老项目 select 构造完全一致:
 * {@code {skillId, text, skillTitle, skillPath}}——skillPath 指向渐进式披露的知识包
 * 路径(系统技能目录下的绝对路径,§7.17 只读放行),由 {@link Skill#knowledgePath()} 提供。
 *
 * <p><b>数据源唯一</b>(目录即真相源,§7.17):{@link SkillCatalog#scan()} 实扫系统
 * 技能目录——内置/插件物化包与用户自制 skill 落盘后统一由此进入菜单,<b>永远全量</b>
 * (旧「内置清单 + 外部扫描 + 插件 SPI」三路合并已收敛为这一路;单目录扫描下目录名
 * 天然唯一,无需去重)。菜单<b>不受</b>按工作区的排除配置影响——排除只过滤
 * system prompt 注入(SkillAdvisor),不影响 `/` 菜单。
 *
 * <p>用户选中后前端只看到胶囊;提交后 opaque 串原样到达后端,由
 * {@link SlashTokenResolveAdvisor} 解析为技能名、知识索引注入沿用 {@code SkillAdvisor}
 * (与老项目「token→技能名 + skill instructions 进上下文」行为等价)。
 */
@Component
public class SkillSlashProvider {

    /** skill 输入 token 的固定 kind(与老项目 {@code SKILL_INPUT_TOKEN_KIND} 一致)。 */
    public static final String SKILL_INPUT_TOKEN_KIND = "system.skill";

    /** `/` 菜单里 skill 候选的分组名(直接作为展示标题)。 */
    private static final String SKILL_SLASH_GROUP = "Skills";

    /** skill 候选在 `/` 菜单里的图标(内联 SVG,currentColor 上色,照搬老项目)。 */
    private static final String SKILL_MENU_ICON =
            "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\""
                    + " stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">"
                    + "<path d=\"M3.2 4.1C3.2 3.5 3.7 3 4.3 3H7.6V13H4.5C3.8 13 3.2 12.4 3.2 11.7V4.1ZM12.8 4.1C12.8 3.5 12.3 3 11.7 3H8.4V13H11.5C12.2 13 12.8 12.4 12.8 11.7V4.1ZM8 5.8L8.7 7.1L10 7.8L8.7 8.5L8 9.8L7.3 8.5L6 7.8L7.3 7.1Z\" /></svg>";

    private final SkillCatalog catalog;

    public SkillSlashProvider(SlashCommandRegistry registry, SkillCatalog catalog) {
        this.catalog = catalog;
        registry.registerProvider("skill", this::load);
    }

    /**
     * 目录扫描 → 全量 `/` 候选项(每 skill 一条,保持 catalog 的 id 升序确定序)。
     * 菜单不受排除配置影响:被某工作区排除(不进 prompt)的 skill 仍在菜单里
     * ——用户显式点名调用某个 skill 不该被静默拦掉。
     */
    private List<SlashCommandItem> load() {
        List<SlashCommandItem> items = new ArrayList<>();
        for (Skill s : catalog.scan()) {
            items.add(toItem(s));
        }
        return items;
    }

    /** 目录扫描到的 skill → `/` 候选项(选中即构造自包含 opaque 串)。 */
    private static SlashCommandItem toItem(Skill skill) {
        String subtitle = skill.description();
        return new SlashCommandItem(
                "skill:" + skill.id(),
                skill.title(),
                subtitle == null || subtitle.isBlank() ? null : subtitle,
                SKILL_MENU_ICON,
                SKILL_SLASH_GROUP,
                SlashTokenEncoder.buildToken(
                        SKILL_INPUT_TOKEN_KIND,
                        skill.title(),
                        subtitle,
                        Json.obj()
                                .put("skillId", skill.id())
                                .put("text", skill.title())
                                .put("skillTitle", skill.title())
                                .put("skillPath", skill.knowledgePath())));
    }
}
