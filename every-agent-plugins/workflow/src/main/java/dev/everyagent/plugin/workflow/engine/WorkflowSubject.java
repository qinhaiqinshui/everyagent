package dev.everyagent.plugin.workflow.engine;

import java.nio.file.Path;
import java.util.Map;

import dev.everyagent.plugin.api.execution.ExecContext;

/**
 * 工作流执行主体标记 —— {@code WorkflowRuntime} 实现本接口。
 *
 * <p>本插件用它做<b>域隔离</b>：{@code WorkflowToolProvider.appliesTo} 判
 * {@code !isWorkflowSubject(ctx)}，使工作流自己的 agent 不携带 workflow CRUD 五工具
 * （防自我编辑/递归管理；这些工具只服务普通任务的 AI——同 subagent 子 agent 剔除
 * agent 族工具的先例）。
 *
 * <p><b>判定通道说明</b>（为什么不是简单 {@code ctx instanceof WorkflowRuntime}）：
 * worker 聚合工具时的 {@code ToolContextImpl} 是<b>委托包装</b>，ToolProvider.appliesTo
 * 拿到的 ctx 无法 instanceof 到底层 ExecContext 的具体类。故约定三通道（任一命中即视为
 * 工作流主体，{@link #isWorkflowSubject} 统一实现）：
 * <ol>
 *   <li>ctx 本身 instanceof 本标记（若未来聚合层直传 ExecContext，此通道即生效）；</li>
 *   <li>{@code metadata()} 含 {@link #MARKER_KEY}=true——WorkflowRuntime 构造时把标记
 *       放进主体 metadata（ExecContext 槽位经委托包装可见，是步骤 5 的实现约定）；</li>
 *   <li>{@code dataDir()} 布局兜底：工作流实例目录 =
 *       {@code workspaces/<wsId>/workflows/instances/<wiId>}（dataDir 向上最多两级——
 *       parent=instances、grandparent=workflows——祖先目录名含 workflows 即命中；
 *       直接父级即 workflows 的同域路径如 {@code workflows/definitions/<wfId>} 同样命中）。
 *       task 域恒为 tasks 布局，二者互斥。</li>
 * </ol>
 */
public interface WorkflowSubject {

    /** 通道②的 metadata 标记键（WorkflowRuntime 实现约定写入 {@code Boolean.TRUE}）。 */
    String MARKER_KEY = "everyagent.workflow.subject";

    /** 通道③向上查找的祖先层级数（parent 与 grandparent 两级）。 */
    int LAYOUT_ANCESTOR_LEVELS = 2;

    /** 布局兜底命中的目录名（workspaces/&lt;wsId&gt;/workflows/instances/&lt;wiId&gt;）。 */
    String LAYOUT_DIR_NAME = "workflows";

    /** 判定 ctx 是否工作流执行主体（三通道见类注释）。 */
    static boolean isWorkflowSubject(ExecContext ctx) {
        if (ctx == null) {
            return false;
        }
        if (ctx instanceof WorkflowSubject) {
            return true;
        }
        Map<String, Object> metadata = ctx.metadata();
        if (metadata != null && Boolean.TRUE.equals(metadata.get(MARKER_KEY))) {
            return true;
        }
        Path dataDir = ctx.dataDir();
        if (dataDir != null) {
            // 实例目录 …/workspaces/<wsId>/workflows/instances/<wiId>：workflows 在两级之上；
            // 兼容直接父级即 workflows 的同域路径。只查两级——更深的同名目录不算（防误伤）。
            Path ancestor = dataDir.getParent();
            for (int level = 0; level < LAYOUT_ANCESTOR_LEVELS && ancestor != null; level++) {
                if (ancestor.getFileName() != null
                        && LAYOUT_DIR_NAME.equals(ancestor.getFileName().toString())) {
                    return true;
                }
                ancestor = ancestor.getParent();
            }
        }
        return false;
    }
}
