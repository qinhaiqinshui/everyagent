// ea: 前端插件入口：默认导出 PluginModule，activate() 注册「编辑器右键项」与「胶囊激活行为」
/**
 * 选区引用 —— 前端插件入口（PluginModule，对标 VSCode 扩展入口）。
 *
 * 加载链路：宿主前端经 plugin.webSource RPC 取 web/index.js（esbuild 产物），
 * 改写 bare import 后以 blob URL import 本模块，再调用 default export 的 activate(ctx)。
 *
 * 硬约束（详见 docs/plugin-guide/web/overview-and-loading.md）：
 *   - 类型一律 import type ... from '@everyagent/plugin-api'（纯类型包，零运行时代码）；
 *   - bare import 白名单只有 5 项：react / react-dom / react/jsx-runtime / antd / @ant-design/icons；
 *     本插件不画组件、不 import React；
 *   - 禁 '@/...' 形式的宿主内部路径引用；
 *   - 产物必须是 web/index.js（webMain 只决定 hasWebMain，实际路径由宿主硬编码）。
 *
 * 本插件做三件事（全部经宿主通用扩展点，零菜单代码、零 DOM 操作、零 opaque 编码）：
 *   1) ui.editor_context_menu_actions → ctx.ui.registerEditorContextMenuAction()
 *      编辑器内容区右键「引用到AI对话框」：把选区经 ctx.ui.appendComposerToken 插入
 *      kind=file.selection 的胶囊（opaque token 编码由宿主单点负责）；打开草稿标签时
 *      带上 workspace 预设（= 选区所属文件的工作区根），新任务即挂靠该工作区
 *      （宿主 task 标签 createTab 的 `workspace` 参数 → 草稿预设，与任务页组「+」同范式）；
 *   2) ui.file_explorer_actions → ctx.ui.registerFileExplorerAction()
 *      资源管理器文件节点右键「引用到AI对话框」：复用宿主 `@` 文件引用的
 *      kind=system.workspace_file 生成等价胶囊（提交解析、胶囊点击打开文件均由宿主
 *      核心内置，本插件零重复实现；仅文件节点显示，与 `@` 引用主用途一致）；
 *   3) ui.composer_chip_actions → ctx.ui.registerComposerChipAction()（file.selection）
 *      点击选区引用胶囊：切回原文件标签并定位选区起始行（经 ctx.ui.openFileTab）。
 */

import type {
  Disposable,
  PluginContext,
  PluginModule,
  UiEditorContextMenuActionDefinition,
  FileExplorerAction,
} from '@everyagent/plugin-api'
import {
  SELECTION_KIND,
  WORKSPACE_FILE_KIND,
  buildSelectionLabel,
  buildWorkspaceFileTokenInput,
  readSelectionPayload,
  type SelectionPayload,
} from './token'

/** 本插件持有的可释放资源；deactivate() 时统一清理（宿主前端当前不自动卸载，保留标准出口）。 */
const disposables: Disposable[] = []

const selectionReferencePlugin: PluginModule = {
  activate(ctx: PluginContext) {
    // ① 编辑器内容区右键项：仅当有选中内容时出现；点击即把选区插入输入框草稿。
    const referenceAction: UiEditorContextMenuActionDefinition = {
      id: 'selection-reference.reference-to-ai',
      label: '引用到AI对话框',
      order: 100,
      // 无选中内容 → 该项不出现（宿主过滤后若无可见项则完全放行原生右键菜单）。
      isVisible: (c) => c.selection.text.length > 0,
      invoke: (c) => {
        const payload: SelectionPayload = {
          workspaceRoot: c.workspaceRoot,
          filePath: c.filePath, // 原样路径：回切标签时命中同一标签
          relativePath: c.relativePath, // 交给 AI 的引用口径
          fileName: c.fileName,
          startLine: c.selection.startLine,
          endLine: c.selection.endLine,
          text: c.selection.text, // 原文不截断
        }
        // 先打开新草稿任务标签页（通用入口），再把选区胶囊写入该标签页草稿。
        // 新标签页的 TaskChat 可能尚未挂载、composerBridge 尚未注入，
        // 由宿主 appendComposerToken → 队列兜底，bridge 注入时自动 flush。
        // workspace 预设 = 选区所属文件的工作区根：新任务必须挂靠文件所在工作区
        // （task 标签 createTab 对 draft + workspace 会先切草稿预设，草稿文本保留）；
        // 不带该参数时宿主会清空预设，草稿回退到注册表首选根 → 工作区选错。
        ctx.ui.openTab('task', { taskId: '__draft__', workspace: c.workspaceRoot }, '新任务')
        // 展开为对象字面量以满足 appendComposerToken 的 Record<string, unknown> 形参
        // （interface 无索引签名，直接传会被 TS 判为不可赋值）。
        ctx.ui.appendComposerToken({
          kind: SELECTION_KIND,
          label: buildSelectionLabel(c.selection.text),
          payload: { ...payload },
        })
      },
    }
    disposables.push(ctx.ui.registerEditorContextMenuAction(referenceAction))

    // ② 资源管理器右键项：仅文件节点显示；点击把文件引用胶囊插入输入框草稿。
    //    复用宿主 `@` 文件引用的 kind（system.workspace_file）生成完全等价的胶囊：
    //    - 提交时宿主内置解析器把它还原为「 工作区相对路径 」明文交给 AI（不注入文件内容）；
    //    - 点击胶囊时宿主核心注册的激活行为打开该文件标签（先注册者胜，插件无需也不应注册）。
    //    payload 口径见 token.ts buildWorkspaceFileTokenInput（节点路径 → @ 引用三字段）。
    const fileExplorerAction: FileExplorerAction = {
      id: 'selection-reference.file-reference-to-ai',
      label: '引用到AI对话框',
      isVisible: (c) => c.type === 'file',
      invoke: (c) => {
        const tokenInput = buildWorkspaceFileTokenInput(c.path, c.name)
        // 先打开新草稿任务标签页，再把文件引用胶囊写入该标签页草稿。
        // workspace 预设 = 节点所属工作区根（与编辑器右键同范式，见 ① 内注释）。
        ctx.ui.openTab('task', { taskId: '__draft__', workspace: c.workspaceRoot }, '新任务')
        ctx.ui.appendComposerToken({
          kind: WORKSPACE_FILE_KIND,
          label: tokenInput.label,
          summary: tokenInput.summary,
          payload: { ...tokenInput.payload },
        })
      },
    }
    disposables.push(ctx.ui.registerFileExplorerAction(fileExplorerAction))

    // ③ 胶囊点击：切回该文件标签并定位选区起始行（仅 file.selection；@ 引用胶囊由宿主核心处理）。
    disposables.push(
      ctx.ui.registerComposerChipAction({
        kind: SELECTION_KIND,
        pluginId: 'selection-reference',
        onActivate: (c) => {
          const p = readSelectionPayload(c.token.payload)
          if (!p) {
            return // 缺字段 → 静默 no-op，不抛错
          }
          ctx.ui.openFileTab(p.workspaceRoot, p.filePath, {
            lineNumber: p.startLine ?? undefined,
          })
        },
      }),
    )
  },

  // 宿主前端当前从不调用 deactivate（loadedPlugins 的 disposables 恒空），但保留标准出口：
  // 一旦将来接入热卸载，这里能把所有注册项从各扩展点注册表里摘除。
  deactivate() {
    for (const d of disposables.splice(0)) {
      d.dispose()
    }
  },
}

export default selectionReferencePlugin