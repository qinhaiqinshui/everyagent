package dev.everyagent.plugin.api.exception;

/**
 * 插件依赖的本机工具/环境不可用(如未找到 git 可执行文件)。
 * RpcDispatcher 捕获后以 rpc.err code=UNAVAILABLE 回前端,前端据此静默降级
 * (侧边栏提示不可用),不弹错误框。
 */
public class UnavailableException extends RuntimeException {
    public UnavailableException(String message) {
        super(message);
    }
}
