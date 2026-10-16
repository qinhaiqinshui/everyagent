package dev.everyagent.plugin.selectionReference;

/**
 * 「文件选区引用」胶囊的 worker 侧契约常量。
 *
 * <p>职责：
 * <ul>
 *   <li>固定 {@link #KIND} = {@code file.selection}，与前端 {@code web/token.ts} 的
 *       {@code SELECTION_KIND} 逐字一致（前端经 {@code ctx.ui.appendComposerToken} 插入该 kind
 *       的胶囊，宿主单点编码 opaque token）；</li>
 *   <li>集中 payload 字段名常量，供 {@link SelectionSlashResolver} 与测试共用，
 *       避免字段名字面量散落。</li>
 * </ul>
 *
 * <p>payload 由前端在右键「引用到AI对话框」时构造，字段见下；解析器对缺字段 / 类型不符
 * 一律降级不抛错（见 {@link SelectionSlashResolver}）。
 */
public final class SelectionReferenceToken {

    /** 文件选区引用 token 的固定 kind（前端插入 / 后端提交解析共用）。 */
    public static final String KIND = "file.selection";

    /** 所属工作区根（回切标签用，前端 payload 自带）。 */
    public static final String FIELD_WORKSPACE_ROOT = "workspaceRoot";
    /** 标签身份里的原样路径。 */
    public static final String FIELD_FILE_PATH = "filePath";
    /** 归一化工作区相对路径（交给 AI 的引用口径）。 */
    public static final String FIELD_RELATIVE_PATH = "relativePath";
    /** 文件名。 */
    public static final String FIELD_FILE_NAME = "fileName";
    /** 选区起始行（1-based；缺失为 null）。 */
    public static final String FIELD_START_LINE = "startLine";
    /** 选区结束行（1-based；缺失为 null）。 */
    public static final String FIELD_END_LINE = "endLine";
    /** 选中原文（不截断）。 */
    public static final String FIELD_TEXT = "text";

    private SelectionReferenceToken() {
    }
}