package dev.everyagent.plugin.contextcompression;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.config.WorkerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextCompressionPlugin implements EveryAgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(ContextCompressionPlugin.class);

    @Override
    public String id() { return "context-compression"; }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        WorkerConfig config = ctx.services().config();
        WorkerServices services = ctx.services(); // 供 provider 延迟解析 TokenEstimator
        // 手动压缩请求注册表:请求登记节点(881)与压缩 advisor 共享同一实例
        ManualCompressRegistry manualRequests = new ManualCompressRegistry();
        ctx.registerAdvisorProvider(new ContextCompressionAdvisorProvider(config, services, manualRequests));
        ctx.registerTaskLifecycleNode(new CompressContextRequestNode(manualRequests));
        ctx.registerSlashProvider("compress-context", () ->
                CompressContextSlashProvider.items(ctx.services()));
        ctx.registerSlashTokenResolver(new CompressContextSlashResolver());
        log.info("[context-compression] 已注册 ContextCompressionAdvisorProvider + compress.request 节点");
    }
}
