package com.codex.sqltuner.config;

import com.codex.sqltuner.tuning.TuningTaskRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminRuntimeHealthServiceTest {
    @Test
    void keepsOperationalDetailsOnTheAdministratorEndpoint() {
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        TuningTaskRepository taskRepository = mock(TuningTaskRepository.class);
        RetentionProperties retention = new RetentionProperties();
        retention.setEnabled(true);
        retention.setConversationDays(7);
        ReadinessService readinessService = mock(ReadinessService.class);
        RuntimeHealthView modelHealth = new RuntimeHealthView(
                "openai-compatible", "test-model", "ready", true);
        when(modelConfigService.healthView()).thenReturn(modelHealth);
        when(taskRepository.queuedCount()).thenReturn(6);
        when(taskRepository.runningCount()).thenReturn(3);
        when(readinessService.current()).thenReturn(new ReadinessStatus("UP", "UP", "UP"));

        RuntimeHealthView health = new AdminRuntimeHealthService(
                modelConfigService, taskRepository, retention, readinessService).view();

        assertThat(health.getMysql()).isEqualTo("UP");
        assertThat(health.getScheduler()).isEqualTo("UP");
        assertThat(health.getQueued()).isEqualTo(6);
        assertThat(health.getRunning()).isEqualTo(3);
        assertThat(health.isRetentionEnabled()).isTrue();
        assertThat(health.getRetentionDays()).isEqualTo(7);
    }
}
