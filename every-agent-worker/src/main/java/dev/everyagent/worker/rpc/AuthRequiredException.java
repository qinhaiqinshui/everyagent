package dev.everyagent.worker.rpc;

/**
 * 业务方需要用户补充凭证(如 git 远端认证失败且无可用凭证)。
 * RpcDispatcher 捕获后以 rpc.err code=AUTH_REQUIRED 回前端,前端据此弹窗收集后重试。
 */
public class AuthRequiredException extends RuntimeException {

    /** 需要认证的远端 host(如 codeup.aliyun.com),供前端弹窗展示。 */
    private final String host;

    public AuthRequiredException(String host) {
        super("需要认证: " + host);
        this.host = host;
    }

    public String host() {
        return host;
    }
}
