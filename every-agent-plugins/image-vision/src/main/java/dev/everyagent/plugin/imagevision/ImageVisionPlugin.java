package dev.everyagent.plugin.imagevision;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * image-vision 插件入口 —— activate 只做一件事：注册 {@link ImageReadHandler}。
 *
 * <p>@ 引用图片不再自动注入附件（与文本文件一致只留路径文本）；AI 需要看图时自己调
 * {@code read_file}，由核心 {@code FileTools} 按扩展名分发到本插件的图片接管处理器，
 * 附件由核心合并进任务 metadata 并经 {@code FileAttachmentAdvisor} 注入模型；本插件
 * 只提供图片扩展名的读取/压缩/转 base64 处理逻辑（红线：复用核心机制，一个插件只
 * 注册 handler，不做 Advisor/节点）。
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
        ctx.registerFileReadHandler(new ImageReadHandler(settings));
        log.info("[image-vision] 已注册 read_file 图片接管处理器 ImageReadHandler: enabled={}, 上限={} bytes(base64), 扩展名={}",
                settings.enabled(), settings.maxBase64Bytes(), settings.extensions());
    }
}
