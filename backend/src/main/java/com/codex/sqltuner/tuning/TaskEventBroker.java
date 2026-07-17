package com.codex.sqltuner.tuning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class TaskEventBroker {
    private static final Logger log = LoggerFactory.getLogger(TaskEventBroker.class);
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>();

    public SseEmitter subscribe(Long taskId, SqlTuningTask snapshot) {
        final SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);
        emitters.computeIfAbsent(taskId, new java.util.function.Function<Long, CopyOnWriteArrayList<SseEmitter>>() {
            @Override
            public CopyOnWriteArrayList<SseEmitter> apply(Long key) {
                return new CopyOnWriteArrayList<SseEmitter>();
            }
        }).add(emitter);
        emitter.onCompletion(new Runnable() {
            @Override
            public void run() {
                remove(taskId, emitter);
            }
        });
        emitter.onTimeout(new Runnable() {
            @Override
            public void run() {
                remove(taskId, emitter);
            }
        });
        send(emitter, "snapshot", snapshot, snapshot.getVersion());
        return emitter;
    }

    public void publish(SqlTuningTask task) {
        String event = task.getStatus() == TaskStatus.DONE ? "result" : task.getStatus() == TaskStatus.FAILED ? "task-error" : "status";
        List<SseEmitter> list = emitters.get(task.getId());
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            if (!send(emitter, event, task, task.getVersion())) {
                remove(task.getId(), emitter);
            }
        }
    }

    public void publishArtifact(SqlTuningTask task, HarnessArtifact artifact) {
        List<SseEmitter> list = emitters.get(task.getId());
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            if (!send(emitter, "artifact", artifact, task.getVersion())) {
                remove(task.getId(), emitter);
            }
        }
    }

    @Scheduled(fixedDelay = 25000L)
    public void heartbeat() {
        for (Map.Entry<Long, CopyOnWriteArrayList<SseEmitter>> entry : emitters.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                if (!send(emitter, "heartbeat", "ok", null)) {
                    remove(entry.getKey(), emitter);
                }
            }
        }
    }

    private boolean send(SseEmitter emitter, String name, Object data, Long eventId) {
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event().name(name).data(data);
            if (eventId != null) {
                event.id(String.valueOf(eventId));
            }
            emitter.send(event);
            return true;
        } catch (IOException e) {
            log.warn("taskEventBroker result 结果: SSE 发送失败, event: {}, reason: {}", name, e.getMessage());
            emitter.complete();
            return false;
        }
    }

    private void remove(Long taskId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(taskId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(taskId);
            }
        }
    }
}
