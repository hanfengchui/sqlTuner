package com.codex.sqltuner.tuning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

/**
 * Keeps task persistence and SSE publication on one state-transition path.
 */
final class TaskExecutionCoordinator {
    private static final Logger log = LoggerFactory.getLogger(TaskExecutionCoordinator.class);

    private final TuningTaskRepository taskRepository;
    private final TaskEventBroker eventBroker;

    TaskExecutionCoordinator(TuningTaskRepository taskRepository, TaskEventBroker eventBroker) {
        this.taskRepository = taskRepository;
        this.eventBroker = eventBroker;
    }

    void transition(SqlTuningTask task, TaskStatus status, String message) {
        task.setStatus(status);
        task.setStatusMessage(message);
        persistWorkerTask(task);
        eventBroker.publish(task);
        log.info("transitionTask result 结果: taskId: {}, status: {}, message: {}", task.getId(), status, message);
    }

    void persistWorkerTask(SqlTuningTask task) {
        persistWorkerTask(task, task.getLeaseOwner(), task.getAttemptCount());
    }

    void persistWorkerTask(SqlTuningTask task, String expectedLeaseOwner, int expectedAttemptCount) {
        if (TuningText.hasText(expectedLeaseOwner)) {
            taskRepository.updateForLease(task, expectedLeaseOwner, expectedAttemptCount);
            return;
        }
        taskRepository.update(task);
    }

    void addArtifact(SqlTuningTask task, String nodeName, String summary, Object payload) {
        HarnessArtifact artifact = new HarnessArtifact(nodeName, summary, payload, LocalDateTime.now());
        task.getArtifacts().add(artifact);
        if (task.getId() != null) {
            taskRepository.appendArtifact(task.getId(), artifact);
            eventBroker.publishArtifact(task, artifact);
        }
    }

    void publish(SqlTuningTask task) {
        eventBroker.publish(task);
    }

    void resetModelStream(SqlTuningTask task) {
        eventBroker.resetModelStream(task);
    }

    void publishModelStream(SqlTuningTask task, TaskStreamChunk chunk) {
        eventBroker.publishModelStream(task, chunk);
    }
}
