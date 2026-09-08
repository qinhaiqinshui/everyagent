package dev.everyagent.contract.ids;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 用户身份源(架构 §13.1):ownerKey = sha256(apiKey) 的 64 位小写 hex,即频道名中的 K。
 * apiKey 即用户 Id(个人部署简化模型);ownerKey 是它的可落盘/可记日志形态——
 * 密钥本身永不进路径、日志、备份。hub/worker/前端三方共用本实现。
 */
public final class Ids {

    private Ids() {
    }

    /** ownerKey = sha256(apiKey),64 位小写 hex。 */
    public static String ownerKey(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }
}
