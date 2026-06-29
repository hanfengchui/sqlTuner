package com.codex.sqltuner.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorConfigTest {
    @Test
    void exposesAsyncAndSseExecutors() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(SseExecutorConfig.class);
        try {
            assertThat(context.getBean("tuningTaskExecutor", Executor.class)).isNotNull();
            assertThat(context.getBean("sseExecutor", Executor.class)).isNotNull();
        } finally {
            context.close();
        }
    }
}
