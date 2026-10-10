package dev.everyagent.plugin.api.rpc;

import tools.jackson.databind.JsonNode;

/**
 * RPC 上下文接口 —— 插件通过此接口处理 RPC 请求。
 *
 * <p>worker 的具体实现持有连接、reqId、params 等运行时状态，
 * 插件仅通过此接口访问参数读取与应答方法。
 */
public interface RpcContext {

    /**
     * 读取必填字符串参数，缺失或空则抛 BadParamsException。
     */
    String strParam(String name);

    /**
     * 读取可选字符串参数，缺失时返回默认值。
     */
    String optStrParam(String name, String def);

    /**
     * 读取可选 long 参数，缺失时返回默认值。
     */
    long optLongParam(String name, long def);

    /**
     * 成功应答（同一 reqId 至多一次）。
     */
    void ok(JsonNode result);

    /**
     * 错误应答（同一 reqId 至多一次）。
     */
    void err(String code, String message);

    /**
     * 原始参数 JsonNode（供插件自行解析复杂参数）。
     */
    JsonNode params();
}
