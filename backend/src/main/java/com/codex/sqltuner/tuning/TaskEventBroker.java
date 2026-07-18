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
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TaskEventBroker {
    private static final Logger log = LoggerFactory.getLogger(TaskEventBroker.class);
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>();
    private final Map<Long, TaskStreamChunk> latestStreams = new ConcurrentHashMap<Long, TaskStreamChunk>();
    private final Map<Long, AtomicLong> streamSequences = new ConcurrentHashMap<Long, AtomicLong>();

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
        TaskStreamChunk stream = latestStreams.get(taskId);
        if (stream != null) {
            send(emitter, "model-stream", stream, null);
        }
        return emitter;
    }

    public void publish(SqlTuningTask task) {
        String event = task.getStatus() == TaskStatus.DONE ? "result" : task.getStatus() == TaskStatus.FAILED ? "task-error" : "status";
        boolean terminal = task.getStatus() == TaskStatus.DONE || task.getStatus() == TaskStatus.FAILED;
        boolean newAttemptBoundary = task.getStatus() == TaskStatus.QUEUED || task.getStatus() == TaskStatus.RECEIVED;
        if (newAttemptBoundary) {
            // 重试或租约恢复后，上一轮未经校验的草稿不再属于当前 attempt。
            latestStreams.remove(task.getId());
        }
        List<SseEmitter> list = emitters.get(task.getId());
        if (list == null || list.isEmpty()) {
            if (terminal) {
                latestStreams.remove(task.getId());
                streamSequences.remove(task.getId());
            }
            return;
        }
        for (SseEmitter emitter : list) {
            if (!send(emitter, event, task, task.getVersion())) {
                remove(task.getId(), emitter);
            }
        }
        if (terminal) {
            latestStreams.remove(task.getId());
            streamSequences.remove(task.getId());
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

    public void publishModelStream(SqlTuningTask task, TaskStreamChunk chunk) {
        if (task == null || task.getId() == null || chunk == null) {
            return;
        }
        AtomicLong sequence = streamSequences.computeIfAbsent(task.getId(), new java.util.function.Function<Long, AtomicLong>() {
            @Override
            public AtomicLong apply(Long key) {
                return new AtomicLong();
            }
        });
        chunk.setSequence(sequence.incrementAndGet());
        chunk.setAttempt(task.getAttemptCount());
        latestStreams.put(task.getId(), chunk);
        List<SseEmitter> list = emitters.get(task.getId());
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            if (!send(emitter, "model-stream", chunk, null)) {
                remove(task.getId(), emitter);
            }
        }
    }

    public void resetModelStream(SqlTuningTask task) {
        if (task == null || task.getId() == null) {
            return;
        }
        latestStreams.remove(task.getId());
        publishModelStream(task, new TaskStreamChunk("RESET", "", 0, 0L));
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
