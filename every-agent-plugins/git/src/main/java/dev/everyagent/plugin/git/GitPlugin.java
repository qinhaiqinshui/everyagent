package dev.everyagent.plugin.git;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.spi.NativeExec;
import dev.everyagent.plugin.api.spi.WorkspaceManager;

/**
 * Git 插件入口。
 *
 * <p>activate() 中实例化 NativeGit → GitCredentialStore → GitService，
 * 注册 GitAutoSyncAdvisorProvider、GitAutoSyncSlashProvider、GitAutoSyncSlashResolver，
 * 以及 git.* RPC 方法。
 */
public class GitPlugin implements EveryAgentPlugin {

    @Override
    public String id() { return "git"; }

    @Override
    public void activate(WorkerPluginContext ctx) {
        WorkerConfig props = ctx.services().config();
        NativeExec sandbox = ctx.services().nativeExec();
        WorkspaceManager workspaces = ctx.services().workspaces();

        // 1. 实例化业务类
        NativeGit git = new NativeGit(props, sandbox);
        GitCredentialStore credentials = new GitCredentialStore();
        GitService gitService = new GitService(workspaces, credentials, git);

        // 2. 注册 GitAutoSyncAdvisorProvider
        ctx.registerAdvisorProvider(new GitAutoSyncAdvisorProvider(gitService));

        // 3. 注册 GitAutoSyncSlashProvider
        ctx.registerSlashProvider("git-auto-sync", GitAutoSyncSlashProvider::items);

        // 4. 注册 GitAutoSyncSlashResolver
        ctx.registerSlashTokenResolver(new GitAutoSyncSlashResolver());

        // 5. 注册 git.* RPC 方法
        ctx.registerRpcMethod(GitRpcMethods.GIT_STATUS, gitService::status);
        ctx.registerRpcMethod(GitRpcMethods.GIT_LOG, gitService::log);
        ctx.registerRpcMethod(GitRpcMethods.GIT_DIFF, gitService::diff);
        ctx.registerRpcMethod(GitRpcMethods.GIT_SHOW, gitService::show);
        ctx.registerRpcMethod(GitRpcMethods.GIT_COMMIT, gitService::commit);
        ctx.registerRpcMethod(GitRpcMethods.GIT_PULL, gitService::pull);
        ctx.registerRpcMethod(GitRpcMethods.GIT_PUSH, gitService::push);
        ctx.registerRpcMethod(GitRpcMethods.GIT_DISCARD, gitService::discard);
        ctx.registerRpcMethod(GitRpcMethods.GIT_INIT, gitService::init);
        ctx.registerRpcMethod(GitRpcMethods.GIT_CLONE, gitService::clone);
        ctx.registerRpcMethod(GitRpcMethods.GIT_REMOTE_ADD, gitService::remoteAdd);
        ctx.registerRpcMethod(GitRpcMethods.GIT_REMOTE_LIST, gitService::remoteList);
        ctx.registerRpcMethod(GitRpcMethods.GIT_CREDENTIAL_SAVE, gitService::credentialSave);
    }
}
