// ea: 文件资源管理器节点引用胶囊的 worker 侧契约常量
package dev.everyagent.plugin.selectionReference;

/**
 * 「文件资源管理器节点引用」胶囊的 worker 侧契约常量。
 *
 * <p>职责：
 * <ul>
 *   <li>固定 {@link #KIND} = {@code file.reference}，与前端 {@code web/token.ts} 的
 *       {@code FILE_REFERENCE_KIND} 逐字一致（前端经 {@code ctx.ui.appendComposerToken} 插入该 kind
 *       的胶囊，宿主单点编码 opaque token）；</li>
 *   <li>集中 payload 字段名常量，供 {@link FileReferenceSlashResolver} 与测试共用，
 *       避免字段名字面量散落。</li>
 * </ul>
 *
 * <p>payload 由前端在资源管理器节点右键「引用到AI对话框」时构造，字段见下；解析器对缺字段 /
 * 类型不符一律降级不抛错（见 {@link FileReferenceSlashResolver}）。
 */
public final class FileReferenceToken {

    /** 文件资源管理器节点引用 token 的固定 kind（前端插入 / 后端提交解析共用）。 */
    public static final String KIND = "file.reference";

    /** 所属工作区根（前端 payload 自带）。 */
    public static final String FIELD_WORKSPACE_ROOT = "workspaceRoot";
    /** 节点路径（工作区相对路径）。 */
    public static final String FIELD_PATH = "path";
    /** 节点名称。 */
    public static final String FIELD_NAME = "name";
    /** 节点类型：file 或 directory。 */
    public static final String FIELD_TYPE = "type";

    /** 节点类型常量：文件。 */
    public static final String TYPE_FILE = "file";
    /** 节点类型常量：目录。 */
    public static final String TYPE_DIRECTORY = "directory";

    private FileReferenceToken() {
    }
}
