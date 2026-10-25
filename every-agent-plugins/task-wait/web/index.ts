/**
 * 等待任务（task-wait）—— Web 插件入口（PluginModule，对标 VSCode 扩展入口）。
 *
 * 加载链路：宿主前端经 plugin.webSource RPC 取 web/index.js（esbuild 产物），
 * 改写 bare import 后动态加载本模块，调用 default export 的 activate(ctx)。
 *
 * 硬约束（详见 docs/plugin-guide/web/overview-and-loading.md）：
 *   - 类型一律 import type ... from '@everyagent/plugin-api'（纯类型包，零运行时代码）；
 *   - bare import 白名单：react / react-dom / react/jsx-runtime / antd / @ant-design/icons
 *     （本入口只用 react 的 createElement；.tsx 组件由 esbuild automatic JSX runtime 编译）；
 *   - 禁 '@/...' 形式的宿主内部路径引用。
 *
 * activate 注册四个扩展点消费（规格 §5）：
 *   1) ui.slash_command_providers → 「等待任务」/ 候选（本地 provider）：
 *      select 拉当前工作区进行中任务 → 空则返回空 token（= 没使用 /）→
 *      否则弹选择框（pickerStore）→ 选中后 buildComposerToken 生成内联胶囊；
 *   2) ui.composer_above_panel → 选择框浮层组件（store 未 open 渲染 null）；
 *   3) ui.composer_chip_actions → 点击胶囊打开目标任务标签；
 *   4) ui.kind_views → kind='task.wait' trace 整行接管（读秒 / 终态展示 / 整行可点）。
 */
import React from 'react'
import type {
  Disposable,
  PluginContext,
  PluginKindViewProps,
  PluginModule,
  PluginSlashCommandItem,
} from '@everyagent/plugin-api'
import { KIND, buildLabel, readPayload } from './token'
import { fetchRunningTasks } from './candidates'
import * as pickerStore from './pickerStore'
import WaitTaskPickerPanel from './WaitTaskPickerPanel'
import WaitTraceView from './WaitTraceView'

/** `/` 菜单图标（内联 SVG 字符串，currentColor 上色：时钟，表「等待」；仿 unattended 的菜单图标写法）。 */
const WAIT_MENU_ICON =
  '<svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor"'
  + ' stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
  + '<circle cx="8" cy="8" r="6" /><path d="M8 4.5V8l2.5 1.5" /></svg>'

/** 本插件持有的可释放资源；deactivate() 时统一清理（保留标准出口）。 */
const disposables: Disposable[] = []

const taskWaitPlugin: PluginModule = {
  activate(ctx: PluginContext) {
    // ① `/` 候选 provider：条目在 load 时构造（select 闭包持 ctx）。
    disposables.push(
      ctx.ui.registerSlashCommandProvider({
        id: 'task-wait',
        load: () => [slashItem(ctx)],
      }),
    )

    // ② 选择框浮层（composer 上方面板插槽承载；store 未 open 渲染 null）。
    disposables.push(
      ctx.ui.registerComposerAbovePanel({
        id: 'task-wait-picker',
        Component: WaitTaskPickerPanel,
      }),
    )

    // ③ 点击胶囊（草稿内 / 消息回放内）→ 打开目标任务标签。
    disposables.push(
      ctx.ui.registerComposerChipAction({
        kind: KIND,
        pluginId: 'task-wait',
        onActivate: (c) => {
          const p = readPayload(c.token.payload)
          if (p) {
            ctx.ui.openTab('task', { taskId: p.targetTaskId, title: p.title })
          }
        },
      }),
    )

    // ④ 等待 trace 整行接管：activate 闭包捕获 ctx，经包装组件注入打开目标任务回调
    //    （.ts 入口不用 JSX，节点用 React.createElement 构造）。
    const WaitTraceViewWithCtx = (props: PluginKindViewProps) =>
      React.createElement(WaitTraceView, {
        ...props,
        openTargetTask: (taskId: string, title: string) => {
          ctx.ui.openTab('task', { taskId, title })
        },
      })
    disposables.push(ctx.ui.registerKindView(KIND, WaitTraceViewWithCtx))
  },

  deactivate() {
    for (const d of disposables.splice(0)) {
      d.dispose()
    }
  },
}

/** 构造「等待任务」/ 候选项（select：拉候选 → 空则空 token 短路 → 弹框 → 生成胶囊 token）。 */
function slashItem(ctx: PluginContext): PluginSlashCommandItem {
  return {
    id: 'task-wait:select',
    title: '等待任务',
    subtitle: '选择一个进行中的任务，等待其完成后再继续本任务',
    group: '任务',
    icon: WAIT_MENU_ICON,
    select: async (sctx) => {
      const tasks = await fetchRunningTasks(
        ctx.sdk,
        sctx?.workerId ?? ctx.sdk.workerId,
        sctx?.workspace,
        sctx?.taskId,
      )
      // 没有进行中任务（或拉取失败）→ 空 token：宿主清掉触发段、不插入任何内容
      //（= 相当于没使用 `/`，规格 §1 的「选择失败」路径）
      if (tasks.length === 0) {
        return [{ token: '', position: 'inline' }]
      }
      const picked = await pickerStore.open(tasks)
      // Esc / 点遮罩 / 取消按钮 → 同样按空内容收口
      if (!picked) {
        return [{ token: '', position: 'inline' }]
      }
      const token = ctx.ui.buildComposerToken({
        kind: KIND,
        label: buildLabel(picked.title),
        summary: '等待该任务完成后继续（本次输入有效）',
        payload: { targetTaskId: picked.taskId, title: picked.title },
      })
      return [{ token, position: 'inline' }]
    },
  }
}

export default taskWaitPlugin
