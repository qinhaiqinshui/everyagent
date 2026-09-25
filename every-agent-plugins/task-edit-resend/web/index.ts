/**
 * task-edit-resend 插件 —— Web 插件入口。
 *
 * 注册两个扩展点：
 * - ui.user_message_actions：用户消息旁的编辑按钮（点击进入/取消编辑模式）；
 * - task.submit_contributions：编辑模式下向 task.run 透传 metadata.editSeq
 *   （后端由本插件的 EditResendNode(395.5) 消费截断，核心不解释）。
 */
import type { PluginModule } from '@everyagent/plugin-api'
import EditMessageButton from './EditMessageButton'
import { createEditResendContributionProvider } from './useEditResend'
import './task-edit-resend.css'

const taskEditResendPlugin: PluginModule = {
  activate(ctx) {
    ctx.ui.registerUserMessageAction({
      id: 'task-edit-resend.edit-button',
      Component: EditMessageButton,
    })
    ctx.ui.registerTaskRunSubmitContributionProvider(createEditResendContributionProvider())
  },
}

export default taskEditResendPlugin
