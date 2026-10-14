package dev.everyagent.worker.slash;

import java.util.ArrayList;
import dev.everyagent.plugin.api.skill.PluginSkill;
import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.plugin.registry.SkillContributorRegistry;
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
 * <p><b>数据源</b>(目录即真相源,§7.17):{@link SkillCatalog#scan()} 实扫系统技能
 * 目录——内置/插件物化包与用户自制 skill 落盘后统一由此进入菜单(旧「内置清单 +
 * 外部扫描」两路已收敛为这一路);插件 SPI({@link SkillContributorRegistry})为
 * 过渡期保留的合并分支,届时整体删除。同 id 去重、目录条目优先——SPI 是插件自己的
 * 声明(title/description 完整),目录扫描捞到同 id 只是知识包物化的副产品,不重复
 * 出菜单。插件贡献条目与目录条目同 group/icon/opaque token(交互与选中执行路径完全
 * 一致),仅副标题带「插件 · 」前缀区分来源。
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

    /** 插件贡献 skill 的来源标记(副标题前缀,与目录条目视觉区分)。 */
    private static final String PLUGIN_SOURCE_LABEL = "插件";

    /** 插件贡献 skill 的副标题前缀(标记 + 分隔符)。 */
    private static final String PLUGIN_BADGE = PLUGIN_SOURCE_LABEL + " · ";

    /** skill 候选在 `/` 菜单里的图标(内联 SVG,currentColor 上色,照搬老项目)。 */
    private static final String SKILL_MENU_ICON =
            "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\""
                    + " stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">"
                    + "<path d=\"M3.2 4.1C3.2 3.5 3.7 3 4.3 3H7.6V13H4.5C3.8 13 3.2 12.4 3.2 11.7V4.1ZM12.8 4.1C12.8 3.5 12.3 3 11.7 3H8.4V13H11.5C12.2 13 12.8 12.4 12.8 11.7V4.1ZM8 5.8L8.7 7.1L10 7.8L8.7 8.5L8 9.8L7.3 8.5L6 7.8L7.3 7.1Z\" /></svg>";

    private final SkillCatalog catalog;
    private final SkillContributorRegistry skillContributorRegistry;

    public SkillSlashProvider(SlashCommandRegistry registry,
                              SkillCatalog catalog,
                              SkillContributorRegistry skillContributorRegistry) {
        this.catalog = catalog;
        this.skillContributorRegistry = skillContributorRegistry;
        registry.registerProvider("skill", this::load);
    }

    /**
     * 目录扫描 + 插件 SPI 合并为 `/` 候选项(同 id 先到者胜,目录条目优先)。
     * 条目级合并(而非 Skill 级):SPI 条目直接从 {@link PluginSkill} 构造,
     * 副标题带来源标记;目录条目构造逻辑保持不变。
     */
    private List<SlashCommandItem> load() {
        List<SlashCommandItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Skill s : catalog.scan()) {
            if (seen.add(s.id())) {
                items.add(toItem(s));
            }
        }
        for (PluginSkill ps : skillContributorRegistry.getSkills()) {
            if (seen.add(ps.id())) {
                items.add(toPluginItem(ps));
            }
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

    /**
     * 插件贡献 skill → `/` 候选项:与内置同 group/icon/opaque token 结构(交互与
     * 选中执行路径完全一致,提交后同样经 {@code SkillSlashTokenResolver} 解析),
     * 仅副标题带「插件 · 」前缀区分来源;description 空缺时标记独自成副标题。
     */
    private static SlashCommandItem toPluginItem(PluginSkill ps) {
        String desc = ps.description();
        String subtitle = (desc == null || desc.isBlank()) ? PLUGIN_SOURCE_LABEL : PLUGIN_BADGE + desc;
        return new SlashCommandItem(
                "skill:" + ps.id(),
                ps.title(),
                subtitle,
                SKILL_MENU_ICON,
                SKILL_SLASH_GROUP,
                SlashTokenEncoder.buildToken(
                        SKILL_INPUT_TOKEN_KIND,
                        ps.title(),
                        subtitle,
                        Json.obj()
                                .put("skillId", ps.id())
                                .put("text", ps.title())
                                .put("skillTitle", ps.title())
                                .put("skillPath", ps.knowledgePath())));
    }
}
