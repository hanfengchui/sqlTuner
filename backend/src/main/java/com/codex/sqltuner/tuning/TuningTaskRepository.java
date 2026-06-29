package com.codex.sqltuner.tuning;

import com.codex.sqltuner.common.NotFoundException;
import com.codex.sqltuner.storage.PersistentAppState;
import com.codex.sqltuner.storage.PersistentStateStore;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.function.Function;

@Repository
public class TuningTaskRepository {
    private final PersistentStateStore stateStore;

    public TuningTaskRepository(PersistentStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public SqlTuningTask create(final SqlTuningTask task) {
        // 自增任务序列 + 写入 + 落盘在同一临界区，避免并发创建时序列号重复或丢失。
        return stateStore.mutate(new Function<PersistentAppState, SqlTuningTask>() {
            @Override
            public SqlTuningTask apply(PersistentAppState state) {
                LocalDateTime now = LocalDateTime.now();
                long id = state.getTaskSequence() + 1;
                state.setTaskSequence(id);
                task.setId(id);
                task.setCreatedAt(now);
                task.setUpdatedAt(now);
                state.getTasks().put(task.getId(), task);
                return task;
            }
        });
    }

    public SqlTuningTask getForUser(final Long id, final Long userId) {
        SqlTuningTask task = stateStore.read(new Function<PersistentAppState, SqlTuningTask>() {
            @Override
            public SqlTuningTask apply(PersistentAppState state) {
                return state.getTasks().get(id);
            }
        });
        if (task == null || !task.getUserId().equals(userId)) {
            throw new NotFoundException("调优任务不存在");
        }
        return task;
    }

    public void update(final SqlTuningTask task) {
        // 写回任务状态 + 落盘在同一临界区。同一 task 的更新由 harness 串行触发，锁保证不与其它 repository 交叉撕裂 map。
        stateStore.mutate(new Function<PersistentAppState, Void>() {
            @Override
            public Void apply(PersistentAppState state) {
                task.setUpdatedAt(LocalDateTime.now());
                state.getTasks().put(task.getId(), task);
                return null;
            }
        });
    }
}
