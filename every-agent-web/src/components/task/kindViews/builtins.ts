/**
 * 内置 kind 视图集中注册(「任务线程统一条目与画板纯化」步骤 10)。
 *
 * TaskThread(唯一分发点)副作用 import 本模块完成注册——与 eventRegistry 的模块级
 * 注册同款范式。注册即 KindPayloadMap 编译期配对:各视图以 KindViewProps<'kind'>
 * 泛型签名注册,payload 类型随 kind 锁定(handler 写形状 ↔ 视图读形状)。
 *
 * trace 族(retry/context.compression/model.failover/model.switch/auth.review/
 * system.notice 及 defaultHandler 折入的未来 kind)**不注册专视图**——自然落
 * FallbackView(TaskTraceShell 形状,内容零丢失),新 kind 自动可见。
 */
import { registerKindView } from './registry'
import { UserMessageView } from './userMessageView'
import { MessageView } from './messageView'
import { ToolResultView } from './toolResultView'
import { ErrorView, CancelledView } from './statusViews'

/** 注册全部内置 kind 视图(幂等:重复调用覆盖同 kind,测试/复挂载安全)。 */
export function registerBuiltinKindViews(): void {
  registerKindView('user.message', UserMessageView)
  registerKindView('message', MessageView)
  registerKindView('tool.result', ToolResultView)
  registerKindView('error', ErrorView)
  registerKindView('cancelled', CancelledView)
}

registerBuiltinKindViews()
