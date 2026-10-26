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
 * activate 注册三个扩展点消费（规格 §5）：
 *   1) ui.slash_command_providers → 「等待任务」/ 候选（本地 provider）：
 *      select 拉当前工作区进行中任务 → 空则 toast 提示后返回空 token →
 *      否则经 ctx.ui.showDialog 弹全局模态（宿主出壳 + 本插件出 Content）→
 *      选中后 buildComposerToken 生成内联胶囊；
 *   2) ui.composer_chip_actions → 点击胶囊打开目标任务标签；
 *   3) ui.kind_views → kind='task.wait' trace 整行接管（读秒 / 终态展示 / 整行可点）。
 *
 * 弹窗机制：旧版用 registerComposerAbovePanel + 自管 pickerStore（依附输入框上方
 * 插槽，草稿态漏挂即丢弹窗）；改为 ctx.ui.showDialog（宿主全局挂载的模态，
 * 对齐 UserInteractionHost），从根上消除「弹窗不知道弹到哪里去了」。
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
import type { PickerTask } from './candidates'
import WaitTaskPickerContent from './WaitTaskPickerContent'
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

    // ② 点击胶囊（草稿内 / 消息回放内）→ 打开目标任务标签。
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

    // ③ 等待 trace 整行接管：activate 闭包捕获 ctx，经包装组件注入打开目标任务回调
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

/**
 * 构造「等待任务」/ 候选项（select：拉候选 → 空则 toast → 弹对话框 → 生成胶囊 token）。
 *
 * 弹窗走 ctx.ui.showDialog（宿主全局挂载的模态，对齐 UserInteractionHost），
 * 不再依赖 composer_above_panel 的输入框上方插槽挂载（草稿态会漏挂导致弹窗丢失）。
 * dialog Content 是一个闭包组件，把候选列表经 props 传进 WaitTaskPickerContent。
 */
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
      // 没有进行中任务（或拉取失败）→ 提示用户后返回空 token：宿主清掉触发段、
      // 不插入任何内容（= 相当于没使用 `/`，规格 §1 的「选择失败」路径）。
      if (tasks.length === 0) {
        ctx.ui.showToast('当前工作区没有进行中的任务', 'info')
        return [{ token: '', position: 'inline' }]
      }
      // 弹全局模态：宿主出壳（遮罩/居中浮层/Esc/聚焦），本插件出候选列表 Content。
      // Content 闭包捕获候选列表（每次调用新建组件类型，showDialog 卸载即丢弃）。
      const candidates = tasks
      const Content = (props: { close: (result: PickerTask | null) => void }) =>
        React.createElement(WaitTaskPickerContent, {
          candidates,
          close: props.close,
        })
      const picked = await ctx.ui.showDialog<PickerTask>({
        title: '选择要等待的任务',
        description: '本任务将等待所选任务完成后再继续',
        Content,
      })
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
