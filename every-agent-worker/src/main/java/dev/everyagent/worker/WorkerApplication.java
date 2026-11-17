package dev.everyagent.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = {"dev.everyagent.worker", "dev.everyagent.plugin"})
@ConfigurationPropertiesScan
public class WorkerApplication {

    public static void main(String[] args) {
        preferVirtualThreadBoundedElastic();
        SpringApplication.run(WorkerApplication.class, args);
    }

    /**
     * 全局 {@code Schedulers.boundedElastic()} 切换为 reactor 官方 thread-per-task 实现
     * (JDK 21+ 每任务一虚拟线程),必须在任何 reactor 类加载<b>之前</b>执行——开关是
     * {@code Schedulers.DEFAULT_BOUNDED_ELASTIC_ON_VIRTUAL_THREADS} 静态字段,类初始化时
     * 一次性读取系统属性。
     *
     * <p><b>为什么换</b>(2026-11 t_145g 两次复现 + 池 dump 定案):默认实现按 CPU 核数
     * 建上限个<b>单线程 ScheduledThreadPoolExecutor</b> 执行器,任务入队后靠该唯一线程
     * 从 DelayedWorkQueue 取件。Linux 2 核机(上限 20)池饱和进入 round-robin 复用后,
     * 出现「续体任务已入队(queued=1)、执行器自认忙(active=1)但其工作线程已不存在/
     * 永不取件」——STPE worker 死亡未走 processWorkerExit 纠正,任务静默滞留,任务流
     * 永久挂起零错误(Windows 核多上限高,走不到该路径,故仅 Linux 复现)。thread-per-task
     * 实现的 delay=0 提交路径直接新建虚拟线程执行,无队列等待/唤醒机制,从结构上消除
     * 该缺陷;同时把 worker 级上限按官方建议调大(虚拟线程廉价,原 10×核数对小核机过紧)。
     *
     * <p>幂等纪律:仅当属性未显式配置时才设默认(true / max(64, 10×CPU)),运维可用
     * JVM 参数 {@code -Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=false}
     * 一键回退旧实现。
     */
    static void preferVirtualThreadBoundedElastic() {
        String onVt = "reactor.schedulers.defaultBoundedElasticOnVirtualThreads";
        if (System.getProperty(onVt) == null) {
            System.setProperty(onVt, "true");
        }
        String size = "reactor.schedulers.defaultBoundedElasticSize";
        if (System.getProperty(size) == null) {
            System.setProperty(size, String.valueOf(Math.max(64,
                    10 * Runtime.getRuntime().availableProcessors())));
        }
        System.getLogger("dev.everyagent.worker").log(System.Logger.Level.INFO,
                "boundedElastic 调度器配置: {0}={1}, {2}={3}(JDK>=21 生效 thread-per-task 虚拟线程实现)",
                onVt, System.getProperty(onVt), size, System.getProperty(size));
    }
}

