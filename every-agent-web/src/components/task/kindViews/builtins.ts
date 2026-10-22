/**
 * 内置 kind 视图集中注册(「任务线程统一条目与画板纯化」步骤 10;渲染层两级分发
 * 统一化步骤 3 收敛)。
 *
 * TaskThread(唯一分发点)副作用 import 本模块完成注册——与 eventRegistry 的模块级
 * 注册同款范式。注册即 KindPayloadMap 编译期配对:各视图以 KindViewProps<'kind'>
 * 泛型签名注册,payload 类型随 kind 锁定(handler 写形状 ↔ 视图读形状)。
 *
 * 核心第一层只注册三类:user.message / message / tool.result。trace 族
 * (error/cancelled/retry/context.compression/model.failover/model.switch/
 * auth.review/system.notice 及 defaultHandler 折入的未来 kind)**不注册专视图**——
 * 自然落 FallbackView(TaskTraceShell 形状,内容零丢失),壳内按 wire kind 查第二级
 * traceTypeRegistry 取定义(如 error 的 alert 图标),新 kind 自动可见。
 */
import { registerKindView } from './registry'
import { UserMessageView } from './userMessageView'
import { MessageView } from './messageView'
import { ToolResultView } from './toolResultView'

/** 注册全部内置 kind 视图(幂等:重复调用覆盖同 kind,测试/复挂载安全)。 */
export function registerBuiltinKindViews(): void {
  registerKindView('user.message', UserMessageView)
  registerKindView('message', MessageView)
  registerKindView('tool.result', ToolResultView)
}

registerBuiltinKindViews()
