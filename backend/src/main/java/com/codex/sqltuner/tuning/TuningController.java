package com.codex.sqltuner.tuning;

import com.codex.sqltuner.auth.CurrentUser;
import com.codex.sqltuner.auth.UserAccount;
import com.codex.sqltuner.common.ApiException;
import com.codex.sqltuner.common.ApiResponse;
import com.codex.sqltuner.config.QueueProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpSession;
import javax.validation.Valid;

@RestController
@RequestMapping("/api/tuning/tasks")
public class TuningController {
    private static final Logger log = LoggerFactory.getLogger(TuningController.class);
    private final TuningHarnessService harnessService;
    private final TuningTaskRepository taskRepository;
    private final TaskEventBroker eventBroker;
    private final QueueProperties queueProperties;

    public TuningController(TuningHarnessService harnessService,
                           TuningTaskRepository taskRepository,
                           TaskEventBroker eventBroker,
                           QueueProperties queueProperties) {
        this.harnessService = harnessService;
        this.taskRepository = taskRepository;
        this.eventBroker = eventBroker;
        this.queueProperties = queueProperties;
    }

    @PostMapping
    @Transactional
    public ApiResponse<SqlTuningTask> create(@Valid @RequestBody CreateTuningTaskRequest request,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                             HttpSession session) {
        UserAccount user = CurrentUser.require(session);
        taskRepository.lockQueueAdmission();
        log.info("createTuningTask param 入参: userId: {}, conversationId: {}, dbDialect: {}, sqlLength: {}, deepAnalysis: {}",
                user.getId(), request.getConversationId(), request.getDbDialect(), request.getSqlText().length(), request.getDeepAnalysis());
        SqlTuningTask existing = taskRepository.findByIdempotencyKey(user.getId(), idempotencyKey);
        if (existing != null) {
            existing.setQueuePosition(taskRepository.queuePosition(existing));
            return ApiResponse.ok(taskRepository.publicView(existing));
        }
        if (taskRepository.queuedCount() >= queueProperties.getMaxQueuedGlobal()
                || taskRepository.queuedCountForUser(user.getId()) >= queueProperties.getMaxQueuedPerUser()) {
            throw new ApiException(429, "QUEUE_FULL", "调优队列已满，请稍后重试");
        }
        SqlTuningTask task = harnessService.createTask(user.getId(), request, idempotencyKey);
        task.setQueuePosition(taskRepository.queuePosition(task));
        return ApiResponse.ok(taskRepository.publicView(task));
    }

    @GetMapping("/{id}")
    public ApiResponse<SqlTuningTask> get(@PathVariable("id") Long id, HttpSession session) {
        UserAccount user = CurrentUser.require(session);
        log.info("getTuningTask param 入参: userId: {}, taskId: {}", user.getId(), id);
        SqlTuningTask task = taskRepository.getForUser(id, user.getId());
        task.setQueuePosition(taskRepository.queuePosition(task));
        return ApiResponse.ok(taskRepository.publicView(task));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable("id") final Long id, HttpSession session) {
        final UserAccount user = CurrentUser.require(session);
        log.info("taskEvents param 入参: userId: {}, taskId: {}", user.getId(), id);
        SqlTuningTask task = taskRepository.getForUser(id, user.getId());
        task.setQueuePosition(taskRepository.queuePosition(task));
        return eventBroker.subscribe(id, task);
    }
}
