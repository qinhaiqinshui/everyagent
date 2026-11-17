package dev.everyagent.plugin.contextcompression;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;

/**
 * 「/压缩上下文」斜杠能力 capsule token —— 输入框内联一次性压缩请求标记
 * (仿 task-wait 的 {@code WaitTaskToken}:opaque token 三要素——固定 kind、构造、命中扫描)。
 *
 * <p>本 token 是<b>消费式一次性</b>:用户选中后以普通胶囊插入输入框(inline),随消息
 * 一起提交;{@code CompressContextRequestNode}(轮次循环 881)扫到 rawContent 中的本
 * token 即在 {@code ManualCompressRegistry} 登记一次性请求,{@link ContextCompressionAdvisor}
 * 在本 run 首个模型请求检测到请求即<b>强制压缩上下文</b>(不受触发阈值约束)并当场消费
 * ——用后即弃,不落盘、不残留、不重复触发(多轮工具循环只在首轮压一次)。用户需要再次
 * 压缩时重新选择即可。
 *
 * <p>本 token 不承载任何需 AI 理解的内容;提交解析({@link CompressContextSlashResolver})
 * 返回空串,token 不注入模型上下文。
 */
public final class CompressContextToken {

    /** 压缩上下文 token 的固定 kind(请求登记 / advisor 查询消费用)。 */
    public static final String KIND = "system.compress_context";

    private CompressContextToken() {
    }

    /** 构造压缩上下文 opaque token(默认 select 行为原地插入输入框 → 内联胶囊)。 */
    public static String buildToken() {
        return SlashTokenEncoder.buildToken(
                KIND,
                "压缩上下文",
                "随本条消息发送时强制压缩上下文（一次性，用后即逝）",
                Json.obj().put("enabled", true));
    }

    /**
     * 扫描提交原文中是否含本 kind 的 opaque token 段(深度平衡配对,嵌套整体成段)。
     *
     * @return true = 原文含 /压缩上下文 胶囊(应登记一次性压缩请求)
     */
    public static boolean scanRequest(String rawContent) {
        if (rawContent == null || rawContent.isEmpty()) {
            return false;
        }
        for (String opaque : SlashTokenEncoder.scanTokens(rawContent)) {
            SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(opaque);
            if (parsed != null && KIND.equals(parsed.kind())) {
                return true;
            }
        }
        return false;
    }
}

