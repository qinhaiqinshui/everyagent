/**
 * 轮末文件变更视图(非 trace 插件注册):rounds.jsonl 每轮轻量 fileChanges 的轮末展示。
 * 折叠/展开态均在当前轮最后渲染轻量摘要行,点击行按 roundId 拉全文再开 diff 对比。
 * 旧 trace 插件注册(task.kind='file_changes')已移除:全文改为 task.fileChanges 按轮拉取,
 * 不再从事件日志里提取 file_changes trace。
 */
export { default as RoundFileChangesView } from './ThreadFileChangesTraceView'
export type { RoundFileChangesViewProps } from './ThreadFileChangesTraceView'
