package com.codex.sqltuner.conversation;

import com.codex.sqltuner.auth.CurrentUser;
import com.codex.sqltuner.auth.UserAccount;
import com.codex.sqltuner.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);
    private final ConversationRepository repository;

    public ConversationController(ConversationRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ApiResponse<List<Conversation>> list(HttpSession session) {
        UserAccount user = CurrentUser.require(session);
        log.info("listConversations param 入参: userId: {}", user.getId());
        return ApiResponse.ok(repository.listByUser(user.getId()));
    }

    @PostMapping
    public ApiResponse<Conversation> create(@RequestBody(required = false) CreateConversationRequest request, HttpSession session) {
        UserAccount user = CurrentUser.require(session);
        String title = request == null ? null : request.getTitle();
        log.info("createConversation param 入参: userId: {}, title: {}", user.getId(), title);
        return ApiResponse.ok(repository.create(user.getId(), title));
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<List<Message>> messages(@PathVariable("id") Long id, HttpSession session) {
        UserAccount user = CurrentUser.require(session);
        repository.getForUser(id, user.getId());
        log.info("messages param 入参: userId: {}, conversationId: {}", user.getId(), id);
        return ApiResponse.ok(repository.listMessages(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable("id") Long id, HttpSession session) {
        UserAccount user = CurrentUser.require(session);
        log.info("deleteConversation param 入参: userId: {}, conversationId: {}", user.getId(), id);
        repository.deleteForUser(id, user.getId());
        return ApiResponse.ok(Boolean.TRUE);
    }
}
