package dev.everyagent.contract.frame;

public final class Channels {

    private Channels() {
    }

    /**
     * 命名空间构造(架构 §3.2)。K 即 ownerKey = sha256(apiKey)。
     * contract 只定协议不定业务:具体业务频道名(worker/task/workflow…)归各端自建,
     * hub 只校验频道落在自己的 u.<ownerKey>. 命名空间内。

    /** 命名空间前缀:u.&lt;ownerKey&gt;. */
    public static String ns(String k) {
        return "u." + k + ".";
    }
}
