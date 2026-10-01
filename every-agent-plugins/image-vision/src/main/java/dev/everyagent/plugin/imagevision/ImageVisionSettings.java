package dev.everyagent.plugin.imagevision;

import dev.everyagent.plugin.api.PluginConfig;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * image-vision 插件配置 —— 从 plugin.json contributes.config 解析（{@link PluginConfig}）。
 *
 * @param enabled         图片注入开关（false = handler 放弃处理，图片引用保持纯路径文本）
 * @param maxBase64Bytes  单图大小上限（按 base64 后长度计，字节）
 * @param extensions      支持的图片扩展名集合（小写含点）
 */
public record ImageVisionSettings(boolean enabled, long maxBase64Bytes, Set<String> extensions) {

    /** 默认单图上限：10MB（base64 后）。 */
    public static final int DEFAULT_MAX_SIZE_MB = 10;

    /** 默认支持的图片扩展名。 */
    public static final String DEFAULT_EXTENSIONS = ".png,.jpg,.jpeg,.gif,.webp,.bmp";

    public static ImageVisionSettings from(PluginConfig config) {
        boolean enabled = config.getBoolean("image-vision.enabled", true);
        int mb = config.getInt("image-vision.max-size-mb", DEFAULT_MAX_SIZE_MB);
        if (mb <= 0) {
            mb = DEFAULT_MAX_SIZE_MB;
        }
        String ext = config.getString("image-vision.extensions", DEFAULT_EXTENSIONS);
        return new ImageVisionSettings(enabled, mb * 1024L * 1024L, parseExtensions(ext));
    }

    /** 解析逗号分隔扩展名列表：trim + 小写 + 补前导点；空列表回退默认。 */
    static Set<String> parseExtensions(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String e = part.trim().toLowerCase(Locale.ROOT);
                if (e.isEmpty()) {
                    continue;
                }
                out.add(e.startsWith(".") ? e : "." + e);
            }
        }
        return out.isEmpty() ? parseExtensions(DEFAULT_EXTENSIONS) : Set.copyOf(out);
    }
}
