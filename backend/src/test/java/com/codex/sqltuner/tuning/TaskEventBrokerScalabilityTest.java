package com.codex.sqltuner.tuning;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskEventBrokerScalabilityTest {
    @Test
    void oneHundredSubscribersShareBrokerWithoutPerConnectionThreads() throws Exception {
        TaskEventBroker broker = new TaskEventBroker();
        SqlTuningTask snapshot = new SqlTuningTask();
        snapshot.setId(42L);
        snapshot.setStatus(TaskStatus.QUEUED);
        snapshot.setStatusMessage("queued");
        long threadsBefore = liveThreadCount();

        for (int i = 0; i < 100; i++) {
            broker.subscribe(snapshot.getId(), snapshot);
        }

        long threadsAfter = liveThreadCount();
        assertThat(subscriberCount(broker, snapshot.getId())).isEqualTo(100);
        assertThat(threadsAfter - threadsBefore).isLessThanOrEqualTo(2L);
    }

    @Test
    void keepsLatestModelStreamForReconnectAndCleansOnTerminalState() throws Exception {
        TaskEventBroker broker = new TaskEventBroker();
        SqlTuningTask task = new SqlTuningTask();
        task.setId(51L);
        task.setStatus(TaskStatus.LLM_ANALYZING);
        task.setStatusMessage("analyzing");
        task.setVersion(8L);

        broker.publishModelStream(task, new TaskStreamChunk("THINKING", "", 80, 1L));
        broker.publishModelStream(task, new TaskStreamChunk("ANSWER", "先确认访问路径。", 128, 2L));

        assertThat(latestStreamCount(broker)).isEqualTo(1);
        assertThat(latestStream(broker, 51L).getSequence()).isEqualTo(2L);
        assertThat(latestStream(broker, 51L).getDraftText()).isEqualTo("先确认访问路径。");
        task.setStatus(TaskStatus.QUEUED);
        broker.publish(task);
        assertThat(latestStreamCount(broker)).isZero();
        broker.publishModelStream(task, new TaskStreamChunk("VERIFYING", "复核已有建议。", 160, 0L));
        assertThat(latestStream(broker, 51L).getSequence()).isEqualTo(3L);
        assertThat(latestStream(broker, 51L).getAttempt()).isEqualTo(task.getAttemptCount());

        broker.resetModelStream(task);
        assertThat(latestStream(broker, 51L).getPhase()).isEqualTo("RESET");
        assertThat(latestStream(broker, 51L).getSequence()).isEqualTo(4L);

        task.setStatus(TaskStatus.DONE);
        broker.publish(task);
        assertThat(latestStreamCount(broker)).isZero();
        assertThat(streamSequenceCount(broker)).isZero();
    }

    @SuppressWarnings("unchecked")
    private int subscriberCount(TaskEventBroker broker, Long taskId) throws Exception {
        Field field = TaskEventBroker.class.getDeclaredField("emitters");
        field.setAccessible(true);
        Map<Long, List<SseEmitter>> emitters = (Map<Long, List<SseEmitter>>) field.get(broker);
        return emitters.get(taskId).size();
    }

    @SuppressWarnings("unchecked")
    private int latestStreamCount(TaskEventBroker broker) throws Exception {
        Field field = TaskEventBroker.class.getDeclaredField("latestStreams");
        field.setAccessible(true);
        Map<Long, TaskStreamChunk> streams = (Map<Long, TaskStreamChunk>) field.get(broker);
        return streams.size();
    }

    @SuppressWarnings("unchecked")
    private TaskStreamChunk latestStream(TaskEventBroker broker, Long taskId) throws Exception {
        Field field = TaskEventBroker.class.getDeclaredField("latestStreams");
        field.setAccessible(true);
        Map<Long, TaskStreamChunk> streams = (Map<Long, TaskStreamChunk>) field.get(broker);
        return streams.get(taskId);
    }

    @SuppressWarnings("unchecked")
    private int streamSequenceCount(TaskEventBroker broker) throws Exception {
        Field field = TaskEventBroker.class.getDeclaredField("streamSequences");
        field.setAccessible(true);
        Map<Long, ?> streams = (Map<Long, ?>) field.get(broker);
        return streams.size();
    }

    private long liveThreadCount() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        return threadMXBean.getThreadCount();
    }
}
