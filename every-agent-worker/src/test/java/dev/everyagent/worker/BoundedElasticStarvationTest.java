package dev.everyagent.worker;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.everyagent.worker.WorkerApplication;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归测试:boundedElastic 必须不被「其他链的长阻塞工具」互堵(生产缺陷 2026-11
 * t_145g/t_yr7b 三次复现定案,Spring AI 2.0.1 {@code ToolCallingAdvisor} 的
 * {@code toolCallFlux.subscribeOn(boundedElastic)} + reactor 默认实现的串行 Worker
 * 队列互堵,详见 {@code WorkerApplication#installNoQueueBoundedElastic})。
 *
 * <p>测试用与 Spring AI 完全同构的递归链结构(模型流 concatWith defer(工具批+下一轮)
 * .subscribeOn(boundedElastic)),两条链并发:链A 第 2 轮工具长阻塞 8s(模拟
 * wait_agents/ask_user),链B 正常。<b>即使把池上限压到 2 强制 reactor 默认实现进入
 * 复用路径(生产缺陷条件),应用无队列调度器后链B 也必须亚秒级完成</b>——若有人移除
 * 工厂接入(回退默认实现),本测试会在同一强制条件下重新复现互堵而失败(链B 被堵
 * ≈8s),天然守护回归。
 */
class BoundedElasticStarvationTest {

    static final Logger log = LoggerFactory.getLogger(BoundedElasticStarvationTest.class);

    @BeforeEach
    void installNoQueueScheduler() {
        // 生产同款接入(WorkerApplication.main 第一行逻辑);压小 reactor 默认实现的上限,
        // 若接入被移除则 Schedulers.boundedElastic() 回到默认实现并进入 choseOneBusy
        // 复用路径,本测试随即复现互堵而失败——天然守护工厂接入不被移除。
        // 注意:不做 @AfterEach resetFactory——会 shutdown 其他测试类可能持有的调度器
        // 实例(全局单例可变状态,恢复比替换更危险);无队列实现语义完整,保持全局替换
        // 对后续测试类行为兼容(schedule 全语义)。
        System.setProperty("reactor.schedulers.defaultBoundedElasticSize", "2");
        WorkerApplication.installNoQueueBoundedElastic();
    }

    /**
     * 模拟 Spring AI ToolCallingAdvisor 的递归流结构:每轮 = 模型流
     * concatWith(defer(轮次聚合完成→工具批)).subscribeOn(boundedElastic)。
     */
    static Flux<String> chain(String name, int roundsLeft, long blockMsInRound2,
            AtomicInteger toolBatches, AtomicReference<String> stuckAt) {
        if (roundsLeft == 0) {
            return Flux.empty();
        }
        return Flux.just(name + "-model-chunk-r" + roundsLeft)
            .concatWith(Flux.defer(() -> {
                // ← 对应 handleToolCallRecursion 里的 doAfterStream(生产「轮次聚合完成」日志)
                log.info("[{}] 轮次聚合完成 roundsLeft={} thread={}", name, roundsLeft, Thread.currentThread().getName());
                return Flux.deferContextual(ctx -> {
                        // ← 对应 executeToolCalls(生产「工具批开始执行」日志在此)
                        toolBatches.incrementAndGet();
                        log.info("[{}] 工具批开始 roundsLeft={} thread={}", name, roundsLeft, Thread.currentThread().getName());
                        if (blockMsInRound2 > 0 && roundsLeft == 2) {
                            stuckAt.set(name + "-r2");
                            try {
                                Thread.sleep(blockMsInRound2); // 模拟 wait_agents/ask_user 长阻塞工具
                            }
                            catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        return chain(name, roundsLeft - 1, blockMsInRound2, toolBatches, stuckAt);
                    })
                    .subscribeOn(Schedulers.boundedElastic()); // ← 与 Spring AI 完全同源
            }));
    }

    @Test
    void longBlockingToolOnOtherChainMustNotStarveToolBatchScheduling() {
        AtomicInteger toolBatches = new AtomicInteger();
        AtomicReference<String> stuckAt = new AtomicReference<>();

        // 链A:3 轮,第 2 轮工具阻塞 8s(生产里 wait_agents 曾占线 23 分钟)
        Thread chainA = Thread.ofVirtual().start(() ->
            chain("A", 3, 8_000, toolBatches, stuckAt)
                .collectList()
                .block(Duration.ofSeconds(60)));
        // 等 A 进入长阻塞(其 r2 工具批已开始占线)
        long deadline = System.currentTimeMillis() + 20_000;
        while (!"A-r2".equals(stuckAt.get()) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(stuckAt.get()).as("链A 应已进入长阻塞工具(占线)").isEqualTo("A-r2");

        // 链B:2 轮正常工具(毫秒级)——对应生产 t_yr7b 在 t_yr76 的 wait_agents 占线期间推进
        long t0 = System.nanoTime();
        String bResult = chain("B", 2, 0, toolBatches, stuckAt)
            .last()
            .blockOptional(Duration.ofSeconds(15))
            .orElse("<timeout>");
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        log.info("链B 耗时 {}ms 结果 {} 工具批总数 {}", ms, bResult, toolBatches.get());

        // 修复判据:B 不被 A 的长阻塞卡住(被堵则 ≈8000ms,独立调度则亚秒级)
        assertThat(bResult).isEqualTo("B-model-chunk-r1");
        assertThat(ms).as("链B 耗时(ms) — 被链A长阻塞工具互堵则接近 8000").isLessThan(6_000);

        // 收尾:中断链A(模拟用户停止任务),避免拖长测试
        chainA.interrupt();
    }
}
