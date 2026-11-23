package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.api.spi.ToolExecutionChain;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;

/**
 * per-call 工具参数护栏（架构 §7.14.3 现役链序）：**必填参数缺失一律合成失败结果回灌模型，
 * 绝不进入真实调用**。
 *
 * <p><b>动机（2026-12 t_jrm6 事故）</b>：Spring AI 2.0.1 的 {@code MethodToolCallback} 对入参
 * <b>零校验</b>——按形参名 {@code map.get(name)} 取不到就传 {@code null}，未知键静默丢弃。
 * 模型把参数名写错（update_file 的 {@code content} 写成 {@code newcontent}，21 次调用 12 次）时，
 * {@code content} 以 null 直入工具；update_file 曾把 {@code null} 当空串执行，旧内容被原地删空
 * 且返回「已更新」——静默丢内容、无任何错误。本护栏把这类调用拦在真实执行之前，并把
 * 「疑似改名」提示回给模型，下一轮即可自我纠正。
 *
 * <p><b>校验口径</b>（{@link ToolArgumentsSchema}）：对照 {@code ToolDefinition.inputSchema()}
 * （框架按 {@code @ToolParam(required)} 生成的 {@code required} 清单），顶层键缺失或值为
 * JSON null 即缺失。只拦「必填缺失」：<b>多余键只作提示不计失败</b>（不改变框架「未知键被忽略」
 * 语义）；入参非 JSON 对象/不可解析、schema 缺失或无 required → 放行，交框架原生路径
 * （其自带转换失败报错，不重复造轮）。
 *
 * <p><b>合成形态</b>：经 {@code ctx.synthesize(串)} 走方案 B 短路（链尾跳过 observation + 真实
 * 调用）；结果串以 {@link ExecResults#TOOL_FAILURE_PREFIX} 开头 → 下游 tool-events 标 error、
 * 账本记失败、前端红显。<b>order=50</b>：rg-hint(0) 内侧、unattended(100)/tool-events(500)
 * 外侧——合成串照常流经发射节点（被拒调用在时间线可见），且先于一切真实执行。
 */
public final class ToolArgumentsGuardInterceptor implements ToolExecutionInterceptor {

    @Override
    public String id() {
        return "tool-args-guard";
    }

    @Override
    public float order() {
        return 50f;
    }

    @Override
    public String invoke(ToolExecutionContext ctx, ToolExecutionChain next) throws Exception {
        String problem = ToolArgumentsSchema.missingRequired(ctx.toolCallback(), ctx.arguments());
        if (problem != null) {
            ctx.synthesize(ExecResults.TOOL_FAILURE_PREFIX + " " + problem);
        }
        return next.proceed(ctx);
    }
}
