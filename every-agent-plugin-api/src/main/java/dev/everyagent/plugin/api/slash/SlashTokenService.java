package dev.everyagent.plugin.api.slash;

import java.util.List;

/**
 * 任务级 slash token 只读查询服务 —— 任务级开关（/AI 审议、/无人值守、/禁用网络等
 * bottom 胶囊）的<b>唯一真相源读取口</b>。
 *
 * <p>这些开关的真相源是任务目录下的 {@code slash-tokens.json}（斜杠层自管存储，
 * 由 RPC {@code slash.taskTokens.apply} / {@code slash.cancel} 写入与移除），
 * <b>不是</b>任务 {@code metadata}；{@code metadata} 只是随 meta.json 落盘的副本，
 * 任务不在内存时写入会被静默丢弃，曾造成「胶囊已取消、开关仍生效」的双写不一致
 * （本次修复的根因）。
 *
 * <p>消费方：授权链节点（{@code AuthorizationHandler}）、工具执行拦截器、
 * 沙箱命令执行器等，一律经本服务按 kind 查询 token 是否存在来判定开关状态。
 *
 * <p>读取恒以磁盘为准（运行中 / 终态 / 进程重启后一致），无内存态依赖。
 */
public interface SlashTokenService {

    /** 任务当前全部 slash 任务级 token 原始串（任务不存在返回空列表，不抛错）。 */
    List<String> tokensOf(String taskId);

    /** 该任务是否挂了指定 kind 的任务级 token（kind 见各插件的 token 常量，如 ai.review）。 */
    boolean hasKind(String taskId, String kind);
}