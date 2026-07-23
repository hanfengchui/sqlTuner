package com.codex.sqltuner.conversation;

import com.codex.sqltuner.auth.CurrentUser;
import com.codex.sqltuner.auth.UserAccount;
import com.codex.sqltuner.common.ApiResponse;
import com.codex.sqltuner.tuning.SqlTuningTask;
import com.codex.sqltuner.tuning.TuningTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);
    private final ConversationRepository repository;
    private final TuningTaskRepository taskRepository;

    public ConversationController(ConversationRepository repository, TuningTaskRepository taskRepository) {
        this.repository = repository;
        this.taskRepository = taskRepository;
    }

    @GetMapping
    public ApiResponse<List<Conversation>> list(@RequestParam(value = "q", required = false) String search,
                                                @RequestParam(value = "before", required = false) Long before,
                                                @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit,
                                                HttpSession session) {
        UserAccount user = CurrentUser.require(session);
        log.info("listConversations param 入参: userId: {}", user.getId());
        if ((search == null || search.trim().isEmpty()) && before == null && (limit == null || limit == 50)) {
            return ApiResponse.ok(repository.listByUser(user.getId()));
        }
        return ApiResponse.ok(repository.listByUser(user.getId(), search, before, limit == null ? 50 : limit));
    }

    @PostMapping
    public ApiResponse<Conversation> create(@RequestBody(required = false) CreateConversationRequest request, HttpSession session) {
        UserAccount user = CurrentUser.require(session);
        String title = request == null ? null : request.getTitle();
        log.info("createConversation param 入参: userId: {}, title: {}", user.getId(), title);
        return ApiResponse.ok(repository.create(user.getId(), title));
    }

    @GetMapping("/page")
    public ApiResponse<ConversationPage> page(@RequestParam(value = "q", required = false) String search,
                                               @RequestParam(value = "before", required = false) Long before,
                                               @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit,
                                               HttpSession session) {
        UserAccount user = CurrentUser.require(session);
        ConversationPage page = repository.pageByUser(user.getId(), search, before, limit == null ? 50 : limit);
        log.info("pageConversations param 入参: userId: {}, hasSearch: {}, before: {}, limit: {}, items: {}",
                user.getId(), search != null && !search.trim().isEmpty(), before, limit, page.getItems().size());
        return ApiResponse.ok(page);
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<List<Message>> messages(@PathVariable("id") Long id, HttpSession session) {
        UserAccount user = CurrentUser.require(session);
        repository.getForUser(id, user.getId());
        log.info("messages param 入参: userId: {}, conversationId: {}", user.getId(), id);
        return ApiResponse.ok(repository.listMessages(id));
    }

    @GetMapping("/{id}/timeline")
    public ApiResponse<ConversationTimeline> timeline(@PathVariable("id") Long id,
                                                      @RequestParam(value = "before", required = false) Long before,
                                                      @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit,
                                                      HttpSession session) {
        UserAccount user = CurrentUser.require(session);
        repository.getForUser(id, user.getId());
        int safeLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 100));
        List<Message> rows = repository.listMessagesBefore(id, before, safeLimit);
        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) {
            rows = new ArrayList<Message>(rows.subList(1, rows.size()));
        }
        Set<Long> taskIds = repository.taskIds(rows);
        Map<Long, SqlTuningTask> tasksById = new LinkedHashMap<Long, SqlTuningTask>(
                taskRepository.findLeanByIdsForUser(taskIds, user.getId()));
        List<ConversationTimelineItem> items = new ArrayList<ConversationTimelineItem>();
        for (Message message : rows) {
            items.add(new ConversationTimelineItem(message,
                    message.getTaskId() == null ? null : tasksById.get(message.getTaskId())));
        }
        Long nextBefore = hasMore && !rows.isEmpty() ? rows.get(0).getId() : null;
        log.info("timeline param 入参: userId: {}, conversationId: {}, before: {}, limit: {}, items: {}",
                user.getId(), id, before, safeLimit, items.size());
        return ApiResponse.ok(new ConversationTimeline(items, nextBefore, hasMore));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable("id") Long id, HttpSession session) {
        UserAccount user = CurrentUser.require(session);
        log.info("deleteConversation param 入参: userId: {}, conversationId: {}", user.getId(), id);
        repository.deleteForUser(id, user.getId());
        return ApiResponse.ok(Boolean.TRUE);
    }
}
