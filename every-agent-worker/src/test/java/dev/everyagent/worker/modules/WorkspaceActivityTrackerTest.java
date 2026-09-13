package dev.everyagent.worker.modules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 工作区最后活动时间跟踪器(收口附带动作)的单元测试:只验证委托与失败吞掉边界——
 * 收口路径绝不被注册表写入阻塞。更新语义本身由 WorkspaceManagerTest 覆盖。
 */
class WorkspaceActivityTrackerTest {

    @Test
    void onTaskFinishedDelegatesToTouchActivity() {
        WorkspaceManager workspaces = mock(WorkspaceManager.class);
        WorkspaceActivityTracker tracker = new WorkspaceActivityTracker(workspaces);

        tracker.onTaskFinished("/root/ws-a");

        verify(workspaces).touchActivity("/root/ws-a");
        verifyNoMoreInteractions(workspaces);
    }

    @Test
    void onTaskFinishedSwallowsRuntimeFailure() {
        WorkspaceManager workspaces = mock(WorkspaceManager.class);
        doThrow(new IllegalStateException("disk full")).when(workspaces).touchActivity("/root/ws-a");
        WorkspaceActivityTracker tracker = new WorkspaceActivityTracker(workspaces);

        // 不抛异常 = 不阻塞任务收口。
        assertDoesNotThrow(() -> tracker.onTaskFinished("/root/ws-a"));
        verify(workspaces).touchActivity("/root/ws-a");
    }
}
