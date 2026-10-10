package dev.everyagent.plugin.imagevision;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * image-vision 插件入口 —— activate 只做一件事：注册 {@link ImageReferenceHandler}。
 *
 * <p>图片引用的 token 解析、input 改写、attachments 合并由核心
 * {@code FileReferenceProcessNode} 完成，Media 注入由核心 {@code FileAttachmentAdvisor}
 * 完成；本插件只提供图片扩展名的读取/压缩/转 base64 处理逻辑（红线：复用核心机制，
 * 一个插件只注册 handler，不做 Advisor/节点）。
 */
public class ImageVisionPlugin implements EveryAgentPlugin {

    public static final String ID = "image-vision";

    private static final Logger log = LoggerFactory.getLogger(ImageVisionPlugin.class);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void activate(WorkerPluginContext ctx) {
        ImageVisionSettings settings = ImageVisionSettings.from(ctx.config());
        ctx.registerFileReferenceHandler(new ImageReferenceHandler(ctx.services(), settings));
        log.info("[image-vision] 已注册 ImageReferenceHandler: enabled={}, 上限={} bytes(base64), 扩展名={}",
                settings.enabled(), settings.maxBase64Bytes(), settings.extensions());
    }
}
