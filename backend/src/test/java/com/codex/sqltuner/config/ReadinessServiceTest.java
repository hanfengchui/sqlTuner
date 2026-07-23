package com.codex.sqltuner.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadinessServiceTest {
    @Test
    void cachesMysqlAndSchedulerProbeForTheConfiguredShortWindow() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SchedulerHealth schedulerHealth = mock(SchedulerHealth.class);
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(schedulerHealth.isHealthy()).thenReturn(true);
        ReadinessService service = new ReadinessService(jdbcTemplate, schedulerHealth, 1000L);

        ReadinessStatus first = service.current();
        ReadinessStatus second = service.current();

        assertThat(first.isUp()).isTrue();
        assertThat(second.getScheduler()).isEqualTo("UP");
        verify(jdbcTemplate, times(1)).queryForObject(eq("SELECT 1"), eq(Integer.class));
        verify(schedulerHealth, times(1)).isHealthy();
    }

    @Test
    void reportsDownWhenSchedulerHasStoppedEvenIfMysqlIsReachable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SchedulerHealth schedulerHealth = mock(SchedulerHealth.class);
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(schedulerHealth.isHealthy()).thenReturn(false);

        ReadinessStatus status = new ReadinessService(jdbcTemplate, schedulerHealth, 1000L).current();

        assertThat(status.getStatus()).isEqualTo("DOWN");
        assertThat(status.getMysql()).isEqualTo("UP");
        assertThat(status.getScheduler()).isEqualTo("DOWN");
    }
}
