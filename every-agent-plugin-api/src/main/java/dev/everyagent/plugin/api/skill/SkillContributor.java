package dev.everyagent.plugin.api.skill;

import java.util.List;

/**
 * Skill 贡献者 SPI —— 插件实现此接口向 system prompt 与 {@code /} 菜单贡献 skill。
 *
 * <p>对标 {@code BuiltInSkills} 的内置 skill 装配，但由外部插件提供。
 * 第一个使用者是 subagent 插件贡献 {@code agent-dispatch} skill。
 *
 * <p>红线：skill 的知识注入与工具授权一律交给 Spring AI 的 advisor / tool 原语，
 * 本接口只负责声明 skill 元数据（id/title/description/知识包路径/toolIds）。
 */
public interface SkillContributor {

    /** 插件 id（与 plugin.json 一致）。 */
    String pluginId();

    /**
     * 贡献的 skill 列表。
     * <p>每个 {@link PluginSkill} 携带 id/title/description/knowledgePath/toolIds。
     * knowledgePath 为宿主绝对路径（知识包物化后的位置），AI 按需 read_file 读取。
     */
    List<PluginSkill> skills();
}
