package dev.everyagent.hub.ws;

import dev.everyagent.contract.frame.Channels;

/**
 * 频道名校验(架构 §3.2 / §13.2)。hub 不理解业务:没有频道角色矩阵、没有内容校验,
 * 唯一规则是身份边界——频道必须落在连接自己的 u.&lt;ownerKey&gt;. 命名空间内,且字符集/长度合法。
 * 命名空间内(即持有同一 apiKey)的连接之间互信,可自由 sub/pub 任意频道;
 * 业务归属校验(如"这个任务是不是你的")由业务端(worker)自行完成。
 */
public final class ChannelRules {

    private ChannelRules() {
    }

    /** 返回 null 表示合法,否则为拒绝原因。 */
    public static String channelError(String channel, String ownerKey) {
        if (channel == null || channel.isEmpty() || channel.length() > 160) {
            return "channel length must be 1..160";
        }
        String prefix = Channels.ns(ownerKey);
        if (!channel.startsWith(prefix)) {
            return "channel must be in own namespace u.<ownerKey>.*: " + channel;
        }
        String suffix = channel.substring(prefix.length());
        if (suffix.isEmpty() || !suffix.matches("[a-z0-9._-]+")) {
            return "channel charset must be [a-z0-9._-]: " + channel;
        }
        return null;
    }

    /** 频道名里去掉 u.<ownerKey>. 前缀后的部分;调用前须已通过 channelError。 */
    public static String suffix(String channel, String ownerKey) {
        return channel.substring(Channels.ns(ownerKey).length());
    }
}
