package dev.everyagent.plugin.contextcompression;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;

/**
 * 「/压缩上下文」斜杠能力 capsule token —— 任务级一次性压缩请求标记(仿
 * {@code UnattendedToken}:opaque token 三要素——固定 kind、构造、命中扫描)。
 *
 * <p>与 unattended/ai-review 等<b>持久开关</b>不同,本 token 是<b>消费式一次性</b>:
 * 用户选中后 token 写入任务目录 {@code slash-tokens.json},{@link ContextCompressionAdvisor}
 * 在下一次模型请求时检测到本 token 即<b>强制压缩上下文</b>(不受触发阈值约束),
 * 压缩完成后经 {@code SlashTokenService.removeKind} 消费(移除)本 token ——
 * 不残留、不重复触发。用户需要再次压缩时重新选择即可。
 *
 * <p>本 token 不承载任何需 AI 理解的内容;提交解析({@link CompressContextSlashResolver})
 * 返回空串,token 不注入模型上下文。真正的压缩动作由 {@link ContextCompressionAdvisor}
 * 在 advisor 链内按任务级 token 查询判定完成。
 */
public final class CompressContextToken {

    /** 压缩上下文 token 的固定 kind(任务级 token 查询 / 消费用)。 */
    public static final String KIND = "system.compress_context";

    private CompressContextToken() {
    }

    /** 构造压缩上下文 opaque token(select 直接返回该串 → 底部渲染胶囊)。 */
    public static String buildToken() {
        return SlashTokenEncoder.buildToken(
                KIND,
                "压缩上下文",
                "下次发送消息时强制压缩上下文（一次性，用后自动消失）",
                Json.obj().put("enabled", true));
    }
}
