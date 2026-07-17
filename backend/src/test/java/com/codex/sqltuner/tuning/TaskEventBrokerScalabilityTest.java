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

    @SuppressWarnings("unchecked")
    private int subscriberCount(TaskEventBroker broker, Long taskId) throws Exception {
        Field field = TaskEventBroker.class.getDeclaredField("emitters");
        field.setAccessible(true);
        Map<Long, List<SseEmitter>> emitters = (Map<Long, List<SseEmitter>>) field.get(broker);
        return emitters.get(taskId).size();
    }

    private long liveThreadCount() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        return threadMXBean.getThreadCount();
    }
}
