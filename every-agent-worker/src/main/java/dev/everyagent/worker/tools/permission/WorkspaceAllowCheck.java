package dev.everyagent.worker.tools.permission;

import org.springframework.stereotype.Component;

/**
 * 责任链节点 1:工作区内放行。
 * realPath 落在工作区 realpath 之内即 ALLOW(符号链接逃逸/绕行都以 realpath 为准)。
 */
@Component
public class WorkspaceAllowCheck implements PermissionCheck {

    @Override
    public PermissionDecision check(PermissionContext ctx) {
        if (ctx.realPath() != null && ctx.wsReal() != null
                && ctx.realPath().startsWith(ctx.wsReal())) {
            return PermissionDecision.allow("工作区内");
        }
        return PermissionDecision.skip();
    }
}