package dev.everyagent.worker.skill;

/**
 * skill 领域模型(纯数据,不耦合 Spring AI 或 prompt 构造):目录即真相源,
 * 每个 skill = 系统技能目录 {@code <skillsDir>/} 下一个一级目录 + 目录内的
 * {@code skill.md}(规格见 docs/ARCHITECTURE.md §7.17)。
 *
 * <p><b>渐进式披露</b>:模型只携带「标题 + 一句话描述 + 知识包路径」,
 * 完整方法论正文不进 system prompt(避免每轮全量占用上下文)。知识包由
 * {@link BuiltInSkillPacks}(内置)或插件经 plugin-api 的 {@code SkillMaterializer}
 * 物化到系统技能目录,AI 需要执行某技能时经 {@code read_file} 按
 * {@link #knowledgePath()}(绝对路径,系统技能目录对 AI 工具只读放行)自行读取。
 *
 * <p>展示元数据(title/description)来自 {@code skill.md} 的 frontmatter
 * ({@link SkillFrontmatter},仅 {@code name}+{@code description} 两个键),
 * 缺失时回落「目录名 + 首个非 {@code #} 正文行」。是否进 system prompt
 * <b>不是</b> skill 本体的属性(是 skill × 工作区的关系,由 worker 侧按工作区
 * 排除配置决定,不在本记录、也不在 frontmatter 中)。
 */
public record Skill(

        /** 稳定标识,如 agent-dispatch。 */
        String id,

        /** 展示名。 */
        String title,

        /** 一句话说明何时使用该 skill(渐进式披露的「触发条件」索引)。 */
        String description,

        /**
         * 知识包路径,<b>宿主绝对路径</b>(如 {@code C:\Users\...\.everyagent\skills\agent-dispatch\skill.md})。
         * 指向该 skill 目录下的 {@code skill.md},AI 按需用 {@code read_file} 读取,不随
         * system prompt 全量注入。系统技能目录是系统目录中对 AI 文件工具唯一只读开放的子目录
         * (PermissionGate READ 窄例外 + Sandbox 只读附加根,§7.17),写操作仍硬拒。
         *
         * <p>注入 system prompt 时由 {@link SkillAdvisor} 按当前沙箱后端解析为 AI 可见路径:
         * WSL 系列沙箱下翻译为 {@code /} 开头的沙箱内路径(wsl-direct=/c/...、wsl-bwrap=/mnt/c/...),
         * 使 AI 的 bash({@code cat}/{@code grep})与 {@code read_file}(经 FsToolSupport 反向翻译)
         * 均可直接使用;非 WSL 后端原样注入宿主路径。
         */
        String knowledgePath) {

    /** 渐进式披露条目:标题 + 一句话描述 + 知识包路径,不含方法论正文。 */
    public String toSystemText() {
        return toSystemText(knowledgePath);
    }

    /**
     * 渐进式披露条目(指定知识包路径):用于 WSL 等沙箱环境下注入沙箱内可见路径,
     * 使 AI 的 {@code bash}({@code cat}/{@code grep})与 {@code read_file} 均可直接使用。
     *
     * @param resolvedKnowledgePath 解析后的知识包路径(WSL 沙箱 = /mnt/c/... 或 /c/...;
     *        非 WSL = 原始宿主路径)
     */
    public String toSystemText(String resolvedKnowledgePath) {
        return "- **" + title + "**(`" + id + "`):" + description
                + " 完整方法论在: `" + resolvedKnowledgePath + "`";
    }
}
