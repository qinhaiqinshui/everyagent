package dev.everyagent.worker;

import java.util.concurrent.ThreadFactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@SpringBootApplication(scanBasePackages = {"dev.everyagent.worker", "dev.everyagent.plugin"})
@ConfigurationPropertiesScan
public class WorkerApplication {

    public static void main(String[] args) {
        installNoQueueBoundedElastic();
        SpringApplication.run(WorkerApplication.class, args);
    }

    /**
     * 全局 {@code Schedulers.boundedElastic()} 替换为 {@link NoQueueThreadPerTaskScheduler}
     * (无队列 thread-per-task:每次提交=独立虚拟线程),必须尽早执行——
     * {@code Schedulers.setFactory} 会先 shutdown 既有缓存调度器再替换工厂,晚于业务
     * 使用会中断在途调度,故放 main 第一行(Spring 容器启动前)。
     *
     * <p><b>为什么换</b>(2026-11 生产三次复现 + 本地可复现实验 {@code BoundedElasticStarvationTest}
     * 定案,Spring AI 2.0.1 / reactor 3.8.x):默认实现(含官方 VT 模式)的 Worker 是
     * <b>串行</b>执行器(单线程+任务队列,池满后 choseOneBusy 复用),Spring AI
     * {@code ToolCallingAdvisor} 的工具批调度任务 {@code subscribeOn(boundedElastic)}
     * 会被<b>其他 agent 的长阻塞工具</b>(wait_agents/ask_user/长命令)堵在队列里——
     * 表现为「轮次聚合完成已打、工具批永不开始、任务静默 running」(t_145g/t_yr7b),
     * 取消占线任务后 0.5s 内恢复;2 核小机池小必现,多核机难复现。无队列实现从结构上
     * 消除互堵:工具批调度永不受其他链的长任务影响;长阻塞工具自身占用的是一次性
     * 虚拟线程,worker.dispose() 仍经 interrupt 取消(订阅取消语义不变)。
     *
     * <p>回退开关:JVM 参数 {@code -Deveryagent.worker.noQueueScheduler=false} 恢复
     * reactor 默认实现。
     */
    static void installNoQueueBoundedElastic() {
        if ("false".equalsIgnoreCase(System.getProperty("everyagent.worker.noQueueScheduler"))) {
            System.getLogger("dev.everyagent.worker").log(System.Logger.Level.INFO,
                    "boundedElastic 保留 reactor 默认实现(everyagent.worker.noQueueScheduler=false)");
            return;
        }
        Schedulers.setFactory(new Schedulers.Factory() {
            @Override
            public Scheduler newBoundedElastic(int threadCap, int queuedTaskCap,
                    ThreadFactory threadFactory, int ttlSeconds) {
                return new NoQueueThreadPerTaskScheduler();
            }

            @Override
            public Scheduler newThreadPerTaskBoundedElastic(int threadCap, int queuedTaskCap,
                    ThreadFactory threadFactory) {
                // VT 属性路径(reactor.schedulers.defaultBoundedElasticOnVirtualThreads=true)
                // 同样走串行执行器,一并替换
                return new NoQueueThreadPerTaskScheduler();
            }
        });
        System.getLogger("dev.everyagent.worker").log(System.Logger.Level.INFO,
                "boundedElastic 已替换为无队列 thread-per-task 调度器(eaElastic-N 虚拟线程,"
                        + "工具批调度不再被长阻塞工具互堵)");
    }
}


