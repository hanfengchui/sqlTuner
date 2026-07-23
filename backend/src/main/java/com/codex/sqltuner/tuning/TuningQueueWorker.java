package com.codex.sqltuner.tuning;

import com.codex.sqltuner.config.QueueProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "app.legacy-import.enabled", havingValue = "false", matchIfMissing = true)
public class TuningQueueWorker {
    private static final Logger log = LoggerFactory.getLogger(TuningQueueWorker.class);
    private final TuningTaskRepository taskRepository;
    private final TuningHarnessService harnessService;
    private final TaskEventBroker eventBroker;
    private final QueueProperties queueProperties;
    private final ExecutorService executorService;
    private final Semaphore permits;
    private final String leaseOwner = "sql-tuner-" + UUID.randomUUID().toString();

    public TuningQueueWorker(TuningTaskRepository taskRepository,
                             TuningHarnessService harnessService,
                             TaskEventBroker eventBroker,
                             QueueProperties queueProperties) {
        this.taskRepository = taskRepository;
        this.harnessService = harnessService;
        this.eventBroker = eventBroker;
        this.queueProperties = queueProperties;
        this.permits = new Semaphore(queueProperties.getWorkerCount());
        this.executorService = Executors.newFixedThreadPool(queueProperties.getWorkerCount(), new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "tuning-db-worker-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    @Scheduled(fixedDelay = 1000L)
    public void dispatch() {
        try {
            requeueExpiredLeasesAndPublish();
        } catch (TransientDataAccessException error) {
            log.warn("dispatch deferred after transient DB conflict: {}", error.getClass().getSimpleName());
            return;
        }
        while (permits.tryAcquire()) {
            final SqlTuningTask task;
            try {
                task = taskRepository.claimNext(leaseOwner);
            } catch (TransientDataAccessException error) {
                permits.release();
                log.warn("dispatch deferred after transient DB conflict: {}", error.getClass().getSimpleName());
                return;
            }
            if (task == null) {
                permits.release();
                return;
            }
            eventBroker.publish(task);
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        harnessService.run(task);
                        SqlTuningTask saved = taskRepository.get(task.getId());
                        if (saved.getLeaseOwner() != null) {
                            taskRepository.releaseLease(saved);
                            eventBroker.publish(taskRepository.get(task.getId()));
                        }
                    } catch (Exception error) {
                        handleFailure(task, error);
                    } catch (StackOverflowError error) {
                        // 第三方 AST 遍历可触发此类错误；必须落为终态，不能让租约永久续期。
                        handleFailure(task, error);
                    } finally {
                        permits.release();
                    }
                }
            });
        }
    }

    @Scheduled(fixedDelayString = "${app.queue.heartbeat-seconds:15}000")
    public void heartbeat() {
        try {
            // 长模型调用必须持续续租，避免 90 秒后被其他 worker 重复领取。
            int renewed = taskRepository.renewLeases(leaseOwner);
            if (renewed > 0) {
                log.info("heartbeat result 结果: leaseOwner: {}, renewed: {}", leaseOwner, renewed);
            }
            requeueExpiredLeasesAndPublish();
        } catch (TransientDataAccessException error) {
            log.warn("heartbeat deferred after transient DB conflict: {}", error.getClass().getSimpleName());
        }
    }

    private void handleFailure(SqlTuningTask task, Throwable error) {
        // 异常消息可能包含模型原文或用户 SQL；日志只记录类型和任务 ID。
        log.error("runQueuedTask taskId: {} errorType: {}", task.getId(), error.getClass().getSimpleName());
        // 只能使用本 worker 最后一次成功持有的租约凭据。重新读取任务会拿到新 worker
        // 的 lease_owner，导致旧 worker 在失败路径覆盖已重领任务。
        boolean retry = task.getAttemptCount() < 3 && isRetryable(error);
        String detail = safeFailureDetail(error);
        try {
            SqlTuningTask updated = taskRepository.markAfterFailure(
                    task.getId(),
                    task.getLeaseOwner(),
                    task.getAttemptCount(),
                    task.getVersion(),
                    retry ? TaskStatus.QUEUED : TaskStatus.FAILED,
                    retry ? "临时错误，等待重试: " + detail : "调优失败: " + detail,
                    retry ? "TASK_RETRYABLE" : "TASK_FAILED",
                    retry ? LocalDateTime.now().plusSeconds(5L * task.getAttemptCount()) : null);
            eventBroker.publish(updated);
        } catch (org.springframework.dao.OptimisticLockingFailureException stale) {
            log.warn("runQueuedTask stale failure ignored: taskId: {}, leaseOwner: {}", task.getId(), task.getLeaseOwner());
            eventBroker.publish(taskRepository.get(task.getId()));
        }
    }

    private String safeFailureDetail(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        // 去除换行，截断到数据库字段安全范围；parse/model 边界已避免把原始内容放入 message。
        String normalized = message.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        return normalized.length() <= 400 ? normalized : normalized.substring(0, 400) + "...";
    }

    private boolean isRetryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            // JSON 解析异常继承 IOException，但属于确定性格式失败，绝不能按网络错误重跑整条任务。
            if (current instanceof com.fasterxml.jackson.core.JsonProcessingException) {
                return false;
            }
            if (current instanceof org.springframework.web.client.ResourceAccessException
                    || current instanceof java.io.EOFException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof javax.net.ssl.SSLException) {
                return true;
            }
            if (current instanceof org.springframework.web.client.HttpStatusCodeException) {
                int status = ((org.springframework.web.client.HttpStatusCodeException) current).getRawStatusCode();
                return status == 429 || status == 502 || status == 503 || status == 504;
            }
            // 只信任 LLM 调用边界生成的错误消息；不能扫描解析器/用户数据中的 network 等普通文本。
            if (current instanceof com.codex.sqltuner.llm.LlmCallException) {
                if (((com.codex.sqltuner.llm.LlmCallException) current).isRetryable()) {
                    return true;
                }
                String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(java.util.Locale.ROOT);
                if (message.contains("429") || message.contains("502") || message.contains("503")
                        || message.contains("504") || message.contains("timeout") || message.contains("timed out")
                        || message.contains("connection reset") || message.contains("connection refused")
                        || message.contains("i/o error") || message.contains("提前结束")
                        || message.contains("rate_limit") || message.contains("throttl")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void requeueExpiredLeasesAndPublish() {
        for (SqlTuningTask requeued : taskRepository.requeueExpiredLeases()) {
            eventBroker.publish(requeued);
        }
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }
}
