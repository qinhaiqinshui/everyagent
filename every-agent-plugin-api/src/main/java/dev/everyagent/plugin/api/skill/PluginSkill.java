package dev.everyagent.plugin.api.skill;

import java.util.List;

/**
 * 插件贡献的 skill 描述（主动披露：进 system prompt + {@code /} 菜单）。
 *
 * <p>与 worker 内置 {@code Skill} record 同形：
 * <ul>
 *   <li>{@code id} — 稳定标识（如 {@code agent-dispatch}）</li>
 *   <li>{@code title} — 展示名</li>
 *   <li>{@code description} — 一句话说明何时使用（渐进式披露索引）</li>
 *   <li>{@code knowledgePath} — 宿主绝对路径，AI 按需 read_file 读取</li>
 *   <li>{@code toolIds} — 该 skill 依赖的工具名（文档声明对齐，不额外 gate）</li>
 * </ul>
 */
public record PluginSkill(
        String id,
        String title,
        String description,
        String knowledgePath,
        List<String> toolIds) {

    /** 渐进式披露条目：标题 + 一句话描述 + 知识包路径。 */
    public String toSystemText() {
        return "- **" + title + "**(`" + id + "`):" + description
                + " 完整方法论在: `" + knowledgePath + "`";
    }
}
