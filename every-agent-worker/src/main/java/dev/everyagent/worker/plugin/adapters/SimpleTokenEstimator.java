package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.plugin.api.spi.TokenEstimator;
import org.springframework.stereotype.Component;

/**
 * Fallback Token 估算器（插件未加载 BuiltinTokenEstimator 时使用）。
 *
 * <p>基础估算：CJK 字符 ≈ 1 token、其余 ≈ 4 字符 1 token（无 tokenizer 粗估）。
 * 不做在线校准（factor 恒 1.0）、不持久化。
 *
 * <p>当 model-rate-limit 插件加载时，其 {@code BuiltinTokenEstimator} 经
 * {@code WorkerPluginContext.registerTokenEstimator()} 替换此实例（见 {@link WorkerServicesImpl}）。
 */
@Component
public class SimpleTokenEstimator implements TokenEstimator {

    @Override
    public long estimate(String text, String configId) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return rawTokens(text);
    }

    @Override
    public void calibrate(String configId, long estimatedTokens, long actualTokens) {
        // 不校准
    }

    @Override
    public double factorOf(String configId) {
        return 1.0;
    }

    @Override
    public long sampleCountOf(String configId) {
        return 0;
    }

    static long rawTokens(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        long cjk = 0;
        long other = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            boolean isCjk = sc == Character.UnicodeScript.HAN
                    || sc == Character.UnicodeScript.HIRAGANA
                    || sc == Character.UnicodeScript.KATAKANA
                    || sc == Character.UnicodeScript.HANGUL;
            if (isCjk) {
                cjk++;
            } else if (!Character.isWhitespace(cp) && !Character.isISOControl(cp)) {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }
}
