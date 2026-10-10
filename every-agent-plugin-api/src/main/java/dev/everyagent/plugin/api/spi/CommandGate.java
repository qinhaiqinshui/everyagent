package dev.everyagent.plugin.api.spi;

/**
 * 命令授权门禁 —— 沙箱插件在 spawn 前调用,把「危险动词 / 工作区外路径引用」交给 worker 的
 * 授权决议链(弹窗 / AI 审议)。
 *
 * <p><b>为什么由插件调用而非 worker 包一层</b>:命令执行器归沙箱后端所有(codex / mic / wsl
 * 各自构造进程、自带隔离),worker 无法在不侵入后端的前提下拦截命令串。故把门禁做成窄接口
 * 经 {@link ToolContext#commandGate()} 下发——插件<b>只依赖 contract</b>,worker 的
 * {@code PermissionGate} 仍不暴露到 plugin-api(实现留在 worker 内部)。
 *
 * <p><b>授权通过后 worker 会同步把授权范围下发沙箱</b>(§7.8:沙箱可访问范围 = 授权范围),
 * 故插件<b>无需自己做权限落地</b>——只管「先 authorize(),再执行」。
 *
 * <p><b>拒绝语义</b>:抛出 {@link RuntimeException},其 message 由工具调用回灌模型,命令不执行
 * (与 worker 自身 {@code CommandExecutor} 的门禁行为一致)。
 */
@FunctionalInterface
public interface CommandGate {

    /**
     * 命令执行前的授权检查。
     *
     * @param command 用户原始命令文本(保留引号;插件不得传入加工后的副本)
     * @throws RuntimeException 授权被拒 / 超时;调用方应放弃执行本命令
     */
    void authorize(String command);
}