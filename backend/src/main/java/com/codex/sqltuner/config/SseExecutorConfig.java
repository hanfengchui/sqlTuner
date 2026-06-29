package com.codex.sqltuner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * SSE 轮询专用线程池：每个 /events 连接提交一个最长 ~120s 的轮询任务。
 * 用共享有界池替代每次 Executors.newSingleThreadExecutor()（旧实现每连接建池不 shutdown，泄漏线程）。
 * 池常驻复用，任务结束即归还线程，无泄漏。
 */
@Configuration
public class SseExecutorConfig {
    @Bean(name = "tuningTaskExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor tuningTaskExecutor() {
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                Math.max(2, cores),
                Math.max(8, cores * 2),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(),
                namedThreadFactory("tuning-task-"));
        // 调优任务主要等待模型网关响应，空闲线程允许回收，避免低峰期常驻过多线程。
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @Bean(name = "sseExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor sseExecutor() {
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        // 有界线程 + 无界队列：SSE 连接数可能波动，线程数封顶，超出排队，避免打爆。
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                Math.max(4, cores),
                Math.max(16, cores * 4),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(),
                namedThreadFactory("sse-poll-"));
        // 空闲线程 60s 后回收，减少常驻开销。
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private ThreadFactory namedThreadFactory(final String prefix) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + System.nanoTime());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
