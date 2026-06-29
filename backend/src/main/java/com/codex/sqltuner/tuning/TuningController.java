package com.codex.sqltuner.tuning;

import com.codex.sqltuner.auth.CurrentUser;
import com.codex.sqltuner.auth.UserAccount;
import com.codex.sqltuner.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/tuning/tasks")
public class TuningController {
    private static final Logger log = LoggerFactory.getLogger(TuningController.class);
    private final TuningHarnessService harnessService;
    private final TuningTaskRepository taskRepository;
    private final Executor sseExecutor;

    public TuningController(TuningHarnessService harnessService,
                           TuningTaskRepository taskRepository,
                           @Qualifier("sseExecutor") Executor sseExecutor) {
        this.harnessService = harnessService;
        this.taskRepository = taskRepository;
        this.sseExecutor = sseExecutor;
    }

    @PostMapping
    public ApiResponse<SqlTuningTask> create(@Valid @RequestBody CreateTuningTaskRequest request, HttpSession session) {
        UserAccount user = CurrentUser.require(session);
        log.info("createTuningTask param 入参: userId: {}, conversationId: {}, dbDialect: {}, sqlLength: {}, deepAnalysis: {}",
                user.getId(), request.getConversationId(), request.getDbDialect(), request.getSqlText().length(), request.getDeepAnalysis());
        SqlTuningTask task = harnessService.createTask(user.getId(), request);
        harnessService.runAsync(task.getId(), user.getId());
        return ApiResponse.ok(task);
    }

    @GetMapping("/{id}")
    public ApiResponse<SqlTuningTask> get(@PathVariable("id") Long id, HttpSession session) {
        UserAccount user = CurrentUser.require(session);
        log.info("getTuningTask param 入参: userId: {}, taskId: {}", user.getId(), id);
        return ApiResponse.ok(taskRepository.getForUser(id, user.getId()));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable("id") final Long id, HttpSession session) {
        final UserAccount user = CurrentUser.require(session);
        final SseEmitter emitter = new SseEmitter(120000L);
        log.info("taskEvents param 入参: userId: {}, taskId: {}", user.getId(), id);
        // 用共享有界线程池提交轮询任务，替代旧实现每连接 newSingleThreadExecutor（不 shutdown，泄漏线程）。
        sseExecutor.execute(new Runnable() {
            @Override
            public void run() {
                TaskStatus last = null;
                try {
                    for (int i = 0; i < 120; i++) {
                        SqlTuningTask task = taskRepository.getForUser(id, user.getId());
                        if (task.getStatus() != last) {
                            emitter.send(SseEmitter.event().name("status").data(task));
                            last = task.getStatus();
                        }
                        if (task.getStatus() == TaskStatus.DONE || task.getStatus() == TaskStatus.FAILED) {
                            emitter.send(SseEmitter.event().name("complete").data(task));
                            emitter.complete();
                            return;
                        }
                        TimeUnit.SECONDS.sleep(1);
                    }
                    emitter.complete();
                } catch (IOException e) {
                    log.warn("taskEvents result 结果: SSE 连接断开, taskId: {}, reason: {}", id, e.getMessage());
                    emitter.complete();
                } catch (Exception e) {
                    log.error("taskEvents taskId: {} error 异常: {}", id, e.getMessage(), e);
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }
}
