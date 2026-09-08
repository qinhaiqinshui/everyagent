package dev.everyagent.worker.tools;

import dev.everyagent.worker.authreview.AiAuthReviewer;
import dev.everyagent.worker.authreview.ReviewDecision;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.modules.WorkspaceManager.Root;
import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import dev.everyagent.worker.task.PendingAsks;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.tools.permission.AuthorizeCheck;
import dev.everyagent.worker.tools.permission.CommandCheck;
import dev.everyagent.worker.tools.permission.GrantRegistry;
import dev.everyagent.worker.tools.permission.MissingPathCheck;
import dev.everyagent.worker.tools.permission.OverBroadRootCheck;
import dev.everyagent.worker.tools.permission.PrivilegeCheck;
import dev.everyagent.worker.tools.permission.SkillsReadAllowCheck;
import dev.everyagent.worker.tools.permission.WorkspaceAllowCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PermissionGate AI 安全审议分派单测(plan-unattended-ai-auth 步骤 6,不依赖 Spring):
 * 拦截链两条独立环节——① AI 审议(aiReview=true 且审议器在位):ALLOW 放行(RUN 档落内存、
 * 不弹窗、不发 ask.*)/ DENY 拒绝 / ESCALATE 不确定落到下一环节人工弹窗;
 * fallback=true(deny-on-error=false)→ 同样落到下一环节人工弹窗;aiReview=false 或审议器为
 * null → 跳过审议环节。② 无人值守拦截(unattended=true):无人工可弹,落到弹窗环节的授权请求
 * 直接拒绝;unattended=false → 正常人工弹窗。并发同 key 只触发一次审议(inFlight future 去重)。
 *
 * <p>走 {@link PermissionGate#requirePath} 真实判定入口(工作区外已存在文件),阻断式分派在
 * ensureGranted 内完成;授权附根经 {@link PermissionGate#extraRoots} 断言 RUN 档生效。
 */
class PermissionGateReviewTest {

    @TempDir
    Path ws;
    @TempDir
    Path outside;

    /** 工作区外已存在文件(真实路径:越界 READ 授权请求)。 */
    private Path outsideFile() throws Exception {
        Path f = outside.resolve("secret.txt");
        Files.writeString(f, "OUTSIDE");
        return f;
    }

    private TaskEntry task(boolean aiReview) throws Exception {
        return task(aiReview, false);
    }

    private TaskEntry task(boolean aiReview, boolean unattended) throws Exception {
        ModelSnapshot snap = new ModelSnapshot("cfg", "openai-compat",
                "http://localhost:9999/v1", "m", null);
        TaskEntry t = new TaskEntry("t-1", "任务", snap, "k", ws.toString(), "main-agent", 10_000);
        t.aiReview = aiReview;
        t.unattended = unattended;
        return t;
    }

    private WorkspaceManager workspaceManager() throws Exception {
        WorkspaceManager wm = mock(WorkspaceManager.class);
        when(wm.resolve(anyString())).thenReturn(new Root(ws, ws.toRealPath()));
        return wm;
    }

    private PendingAsks popupAsks() {
        PendingAsks asks = mock(PendingAsks.class);
        try {
            when(asks.ask(any(), anyString(), anyString(), any(), anyList(), anyLong()))
                    .thenReturn(new PendingAsks.AskAnswer("answered", "run"));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        return asks;
    }

    private AiAuthReviewer reviewerReturning(ReviewDecision d) {
        AiAuthReviewer r = mock(AiAuthReviewer.class);
        when(r.review(any(), anyString(), anyString())).thenReturn(d);
        return r;
    }

    private PermissionGate gate(PendingAsks asks, WorkspaceManager wm, AiAuthReviewer reviewer) {
        GrantRegistry grants = new GrantRegistry(asks, new WorkerProperties(), wm,
                mock(TaskStore.class), reviewer);
        return new PermissionGate(wm, grants,
                new WorkspaceAllowCheck(),
                new MissingPathCheck(),
                new SkillsReadAllowCheck(new WorkerProperties()),
                new OverBroadRootCheck(),
                new AuthorizeCheck(grants),
                new CommandCheck(new WorkerProperties(), wm, grants),
                new PrivilegeCheck(grants));
    }

    // ---- aiReview=true:审议短路,不弹窗 ----

    @Test
    void aiReviewAllowsWithoutPopup() throws Exception {
        PendingAsks asks = mock(PendingAsks.class);
        AiAuthReviewer reviewer = reviewerReturning(
                ReviewDecision.of(ReviewDecision.Verdict.ALLOW, 0.9, "安全"));
        PermissionGate gate = gate(asks, workspaceManager(), reviewer);
        Path f = outsideFile();

        gate.requirePath(task(true), "agent-1", f.toString(), PermissionGate.Op.READ);

        // 无弹窗(ask.* 一次未发);审议一次 → 自动按 RUN 档授权,授权根在册
        verify(asks, never()).ask(any(), anyString(), anyString(), any(), anyList(), anyLong());
        verify(reviewer, times(1)).review(any(TaskEntry.class), anyString(), anyString());
        assertTrue(gate.extraRoots("t-1").contains(outside.toRealPath()),
                "ALLOW 审议自动授权(RUN 档)生效: " + gate.extraRoots("t-1"));
        // 同 key 再次请求已放行,不再触发审议
        gate.requirePath(task(true), "agent-1", f.toString(), PermissionGate.Op.READ);
        verify(reviewer, times(1)).review(any(TaskEntry.class), anyString(), anyString());
    }

    @Test
    void aiReviewDenyThrowsWithoutPopup() throws Exception {
        PendingAsks asks = mock(PendingAsks.class);
        AiAuthReviewer reviewer = reviewerReturning(ReviewDecision.deny("危险"));
        PermissionGate gate = gate(asks, workspaceManager(), reviewer);

        assertThrows(PermissionDeniedException.class,
                () -> gate.requirePath(task(true), "agent-1", outsideFile().toString(),
                        PermissionGate.Op.READ));

        verify(asks, never()).ask(any(), anyString(), anyString(), any(), anyList(), anyLong());
        assertTrue(gate.extraRoots("t-1").isEmpty(), "DENY 不产生授权根");
    }

    @Test
    void aiReviewEscalateFallsBackToPopup() throws Exception {
        PendingAsks asks = popupAsks();
        AiAuthReviewer reviewer = reviewerReturning(
                ReviewDecision.of(ReviewDecision.Verdict.ESCALATE, 0.4, "不确定"));
        PermissionGate gate = gate(asks, workspaceManager(), reviewer);

        gate.requirePath(task(true), "agent-1", outsideFile().toString(), PermissionGate.Op.READ);

        // 审议不确定(ESCALATE)不在此拦截,落到下一环节人工弹窗,由用户选择(此处答 run)完成授权
        verify(reviewer, times(1)).review(any(TaskEntry.class), anyString(), anyString());
        verify(asks, times(1)).ask(any(), anyString(), anyString(), any(), anyList(), anyLong());
        assertTrue(gate.extraRoots("t-1").contains(outside.toRealPath()),
                "ESCALATE 回退人工弹窗后按用户选择授权: " + gate.extraRoots("t-1"));
    }

    // ---- 无人值守拦截(独立环节,unattended=true 时落到弹窗环节的授权直接拒绝) ----

    @Test
    void unattendedTrueDeniesWithoutPopup() throws Exception {
        PendingAsks asks = mock(PendingAsks.class);
        AiAuthReviewer reviewer = mock(AiAuthReviewer.class);
        PermissionGate gate = gate(asks, workspaceManager(), reviewer);

        assertThrows(PermissionDeniedException.class,
                () -> gate.requirePath(task(false, true), "agent-1", outsideFile().toString(),
                        PermissionGate.Op.READ));

        // 无人值守直接拒绝:不弹窗、也不走审议(未开 aiReview)
        verify(asks, never()).ask(any(), anyString(), anyString(), any(), anyList(), anyLong());
        verify(reviewer, never()).review(any(TaskEntry.class), anyString(), anyString());
        assertTrue(gate.extraRoots("t-1").isEmpty(), "无人值守拒绝不产生授权根");
    }

    @Test
    void unattendedTrueWithAiReviewEscalateDenies() throws Exception {
        PendingAsks asks = mock(PendingAsks.class);
        AiAuthReviewer reviewer = reviewerReturning(
                ReviewDecision.of(ReviewDecision.Verdict.ESCALATE, 0.4, "不确定"));
        PermissionGate gate = gate(asks, workspaceManager(), reviewer);

        assertThrows(PermissionDeniedException.class,
                () -> gate.requirePath(task(true, true), "agent-1", outsideFile().toString(),
                        PermissionGate.Op.READ));

        // 审议不确定落到弹窗环节,但无人值守开启 → 直接拒绝,不弹窗
        verify(reviewer, times(1)).review(any(TaskEntry.class), anyString(), anyString());
        verify(asks, never()).ask(any(), anyString(), anyString(), any(), anyList(), anyLong());
        assertTrue(gate.extraRoots("t-1").isEmpty(), "无人值守拦截 ESCALATE,不产生授权根");
    }

    @Test
    void unattendedTrueWithAiReviewDenyDenies() throws Exception {
        PendingAsks asks = mock(PendingAsks.class);
        AiAuthReviewer reviewer = reviewerReturning(ReviewDecision.deny("危险"));
        PermissionGate gate = gate(asks, workspaceManager(), reviewer);

        assertThrows(PermissionDeniedException.class,
                () -> gate.requirePath(task(true, true), "agent-1", outsideFile().toString(),
                        PermissionGate.Op.READ));

        verify(reviewer, times(1)).review(any(TaskEntry.class), anyString(), anyString());
        verify(asks, never()).ask(any(), anyString(), anyString(), any(), anyList(), anyLong());
        assertTrue(gate.extraRoots("t-1").isEmpty(), "审议 DENY 拒绝,不产生授权根");
    }

    @Test
    void unattendedTrueWithAiReviewAllowStillAllows() throws Exception {
        PendingAsks asks = mock(PendingAsks.class);
        AiAuthReviewer reviewer = reviewerReturning(
                ReviewDecision.of(ReviewDecision.Verdict.ALLOW, 0.9, "安全"));
        PermissionGate gate = gate(asks, workspaceManager(), reviewer);
        Path f = outsideFile();

        gate.requirePath(task(true, true), "agent-1", f.toString(), PermissionGate.Op.READ);

        // 审议明确 ALLOW 直接放行(不经弹窗环节),无人值守不拦截——两条拦截链互不相关
        verify(reviewer, times(1)).review(any(TaskEntry.class), anyString(), anyString());
        verify(asks, never()).ask(any(), anyString(), anyString(), any(), anyList(), anyLong());
        assertTrue(gate.extraRoots("t-1").contains(outside.toRealPath()),
                "无人值守下审议 ALLOW 仍自动授权: " + gate.extraRoots("t-1"));
    }

    // ---- fallback=true:回退人工弹窗,绝不因审议失败放行 ----

    @Test
    void aiReviewFallbackFallsBackToPopup() throws Exception {
        PendingAsks asks = popupAsks();
        AiAuthReviewer reviewer = reviewerReturning(ReviewDecision.fallback("timeout"));
        PermissionGate gate = gate(asks, workspaceManager(), reviewer);

        gate.requirePath(task(true), "agent-1", outsideFile().toString(), PermissionGate.Op.READ);

        // 审议失败(deny-on-error=false)→ 回退人工弹窗,由用户选择(此处答 run)完成授权
        verify(reviewer, times(1)).review(any(TaskEntry.class), anyString(), anyString());
        verify(asks, times(1)).ask(any(), anyString(), anyString(), any(), anyList(), anyLong());
        assertTrue(gate.extraRoots("t-1").contains(outside.toRealPath()),
                "回退弹窗后按用户选择授权: " + gate.extraRoots("t-1"));
    }

    @Test
    void aiReviewFallbackWithUnattendedTrueDenies() throws Exception {
        PendingAsks asks = mock(PendingAsks.class);
        AiAuthReviewer reviewer = reviewerReturning(ReviewDecision.fallback("timeout"));
        PermissionGate gate = gate(asks, workspaceManager(), reviewer);

        assertThrows(PermissionDeniedException.class,
                () -> gate.requirePath(task(true, true), "agent-1", outsideFile().toString(),
                        PermissionGate.Op.READ));

        // 审议失败落入弹窗环节,但无人值守开启 → 直接拒绝,不弹窗
        verify(reviewer, times(1)).review(any(TaskEntry.class), anyString(), anyString());
        verify(asks, never()).ask(any(), anyString(), anyString(), any(), anyList(), anyLong());
        assertTrue(gate.extraRoots("t-1").isEmpty(), "无人值守拦截审议失败,不产生授权根");
    }

    // ---- aiReview=false:维持人工弹窗现状 ----

    @Test
    void aiReviewFalseStillPopsUp() throws Exception {
        PendingAsks asks = popupAsks();
        AiAuthReviewer reviewer = mock(AiAuthReviewer.class);
        PermissionGate gate = gate(asks, workspaceManager(), reviewer);

        gate.requirePath(task(false), "agent-1", outsideFile().toString(), PermissionGate.Op.READ);

        verify(asks, times(1)).ask(any(), anyString(), anyString(), any(), anyList(), anyLong());
        verify(reviewer, never()).review(any(TaskEntry.class), anyString(), anyString());
        assertTrue(gate.extraRoots("t-1").contains(outside.toRealPath()));
    }

    // ---- 审议器未注入(null):aiReview=true 也不走审议,回退人工弹窗(测试兼容) ----

    @Test
    void aiReviewTrueWithoutReviewerFallsBackToPopup() throws Exception {
        PendingAsks asks = popupAsks();
        PermissionGate gate = gate(asks, workspaceManager(), null);

        gate.requirePath(task(true), "agent-1", outsideFile().toString(), PermissionGate.Op.READ);

        verify(asks, times(1)).ask(any(), anyString(), anyString(), any(), anyList(), anyLong());
        assertTrue(gate.extraRoots("t-1").contains(outside.toRealPath()),
                "审议器为 null 时维持人工弹窗现状");
    }

    // ---- 并发同 key:只触发一次审议(owner 审议,后来者 join 共享结果) ----

    @Test
    void concurrentSameKeyReviewsOnce() throws Exception {
        PendingAsks asks = mock(PendingAsks.class);
        // 审议阻塞一小段,制造并发重叠窗口(owner 审议期间后来者到达 join)
        AiAuthReviewer reviewer = mock(AiAuthReviewer.class);
        AtomicInteger reviewCount = new AtomicInteger();
        when(reviewer.review(any(), anyString(), anyString())).thenAnswer(inv -> {
            reviewCount.incrementAndGet();
            Thread.sleep(300);
            return ReviewDecision.of(ReviewDecision.Verdict.ALLOW, 1.0, "并发审议");
        });
        PermissionGate gate = gate(asks, workspaceManager(), reviewer);
        TaskEntry t = task(true);
        Path f = outsideFile();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger errors = new AtomicInteger();

        Runnable r = () -> {
            try {
                start.await();
                gate.requirePath(t, "agent-1", f.toString(), PermissionGate.Op.READ);
            } catch (Throwable e) {
                errors.incrementAndGet();
            } finally {
                done.countDown();
            }
        };
        Thread a = new Thread(r, "review-a");
        Thread b = new Thread(r, "review-b");
        a.start();
        b.start();
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "并发审议线程未在 5s 内结束");
        a.join(1_000);
        b.join(1_000);

        assertEquals(0, errors.get(), "并发审议双双放行(无异常)");
        assertEquals(1, reviewCount.get(), "并发同 key 只触发一次审议");
        verify(asks, never()).ask(any(), anyString(), anyString(), any(), anyList(), anyLong());
        assertTrue(gate.extraRoots("t-1").contains(outside.toRealPath()),
                "并发审议放行记录一条 RUN 授权根: " + gate.extraRoots("t-1"));
    }
}