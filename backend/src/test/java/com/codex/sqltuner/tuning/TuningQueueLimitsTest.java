package com.codex.sqltuner.tuning;

import com.codex.sqltuner.auth.AuthService;
import com.codex.sqltuner.auth.UserAccount;
import com.codex.sqltuner.auth.UserRole;
import com.codex.sqltuner.common.ApiException;
import com.codex.sqltuner.config.QueueProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TuningQueueLimitsTest {
    @Test
    void createRejectsRequestsWhenGlobalQueueIsFull() {
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setMaxQueuedGlobal(2);
        queueProperties.setMaxQueuedPerUser(10);
        TuningTaskRepository repository = mock(TuningTaskRepository.class);
        when(repository.queuedCount()).thenReturn(2);
        TuningController controller = new TuningController(
                mock(TuningHarnessService.class), repository, new TaskEventBroker(), queueProperties);

        assertThatThrownBy(() -> controller.create(request(), null, session()))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    ApiException api = (ApiException) exception;
                    assertThat(api.getStatus()).isEqualTo(429);
                    assertThat(api.getCode()).isEqualTo("QUEUE_FULL");
                });
    }

    @Test
    void createRejectsRequestsWhenUserQueueIsFull() {
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setMaxQueuedGlobal(100);
        queueProperties.setMaxQueuedPerUser(1);
        TuningTaskRepository repository = mock(TuningTaskRepository.class);
        when(repository.queuedCount()).thenReturn(0);
        when(repository.queuedCountForUser(1L)).thenReturn(1);
        TuningController controller = new TuningController(
                mock(TuningHarnessService.class), repository, new TaskEventBroker(), queueProperties);

        assertThatThrownBy(() -> controller.create(request(), null, session()))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    ApiException api = (ApiException) exception;
                    assertThat(api.getStatus()).isEqualTo(429);
                    assertThat(api.getCode()).isEqualTo("QUEUE_FULL");
                });
    }

    private CreateTuningTaskRequest request() {
        CreateTuningTaskRequest request = new CreateTuningTaskRequest();
        request.setDbDialect("OceanBase/MySQL");
        request.setSqlText("select * from orders");
        request.setDeepAnalysis(Boolean.FALSE);
        return request;
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthService.SESSION_USER,
                new UserAccount(1L, "admin", "Admin", null, UserRole.ADMIN));
        return session;
    }
}
