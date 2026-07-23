package com.codex.sqltuner.integration;

import com.codex.sqltuner.config.ModelEndpointPolicy;
import com.codex.sqltuner.config.QueueProperties;
import com.codex.sqltuner.llm.LlmClient;
import com.codex.sqltuner.llm.LlmRequest;
import com.codex.sqltuner.llm.LlmResponse;
import com.codex.sqltuner.llm.LlmStreamListener;
import com.codex.sqltuner.storage.DatabaseBootstrap;
import com.codex.sqltuner.storage.CryptoSupport;
import com.codex.sqltuner.tuning.SqlTuningTask;
import com.codex.sqltuner.tuning.TaskStatus;
import com.codex.sqltuner.tuning.TaskEventBroker;
import com.codex.sqltuner.tuning.TuningHarnessService;
import com.codex.sqltuner.tuning.TuningQueueWorker;
import com.codex.sqltuner.tuning.TuningTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(FullWorkflowConcurrentRecoveryIntegrationTest.FullWorkflowTestConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:fullworkflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.locations=classpath:db/migration",
        "spring.task.scheduling.enabled=false",
        "llm.provider=mock",
        "llm.model=full-workflow-fake",
        "app.queue.worker-count=10",
        "app.queue.max-running=10",
        "app.queue.max-queued-global=200",
        "app.queue.max-queued-per-user=200",
        "app.queue.lease-seconds=30",
        "app.retention.enabled=false",
        "server.servlet.session.cookie.same-site=lax"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FullWorkflowConcurrentRecoveryIntegrationTest {
    private static final String USER_PASSWORD = "user-test-password";
    private static final int SEEDED_USER_COUNT = 24;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TuningTaskRepository taskRepository;

    @Autowired
    private TuningHarnessService harnessService;

    @Autowired
    private TaskEventBroker eventBroker;

    @Autowired
    private QueueProperties queueProperties;

    @Autowired
    private FullWorkflowStreamingFakeLlmClient fakeLlmClient;

    @MockBean
    private DatabaseBootstrap databaseBootstrap;

    @MockBean
    private TuningQueueWorker scheduledQueueWorker;

    private TuningQueueWorker queueWorker;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM task_stage_results");
        jdbcTemplate.update("DELETE FROM task_input_images");
        jdbcTemplate.update("DELETE FROM task_artifacts");
        jdbcTemplate.update("DELETE FROM tuning_tasks");
        jdbcTemplate.update("DELETE FROM messages");
        jdbcTemplate.update("DELETE FROM conversations");
        jdbcTemplate.update("DELETE FROM skill_versions");
        jdbcTemplate.update("DELETE FROM model_config");
        jdbcTemplate.update("DELETE FROM users");
        LocalDateTime now = LocalDateTime.now();
        String passwordHash = passwordEncoder.encode(USER_PASSWORD);
        for (int i = 0; i < SEEDED_USER_COUNT; i++) {
            jdbcTemplate.update(
                    "INSERT INTO users(id, username, display_name, password_hash, role, enabled, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 'USER', TRUE, ?, ?)",
                    100L + i,
                    usernameForTask(i),
                    "业务用户" + i,
                    passwordHash,
                    Timestamp.valueOf(now),
                    Timestamp.valueOf(now));
        }
        jdbcTemplate.update(
                "INSERT INTO model_config(id, provider, base_url, model, vision_model, encrypted_api_key, timeout_ms, updated_at) "
                        + "VALUES (1, 'mock', '', 'full-workflow-fake', 'full-workflow-fake', NULL, 30000, ?)",
                Timestamp.valueOf(now));
        jdbcTemplate.update(
                "INSERT INTO skill_versions(name, version, content, enabled, updated_at) VALUES "
                        + "('oceanbase-sql-tuning', 1, 'full workflow integration skill', TRUE, ?)",
                Timestamp.valueOf(now));
        fakeLlmClient.reset();
        queueWorker = new TuningQueueWorker(taskRepository, harnessService, eventBroker, queueProperties);
    }

    @AfterEach
    void shutdownWorker() {
        if (queueWorker != null) {
            queueWorker.shutdown();
        }
    }

    @Test
    void tenIndependentUserSessionsSubmitWithCsrfStreamThroughSseAndPersistTerminalResults() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        try {
            List<Future<SubmittedTask>> futures = new ArrayList<Future<SubmittedTask>>();
            for (int i = 0; i < 10; i++) {
                final int taskNumber = i;
                futures.add(executor.submit(() -> submitTaskFromIndependentSession(taskNumber)));
            }
            List<SubmittedTask> submitted = new ArrayList<SubmittedTask>();
            for (Future<SubmittedTask> future : futures) {
                submitted.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(distinctTaskIds(submitted)).hasSize(10);
            assertThat(distinctSessionIdentities(submitted)).hasSize(10);
            assertThat(distinctUserIdsForSubmittedTasks(submitted)).hasSize(10);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tuning_tasks WHERE status = 'QUEUED'", Integer.class))
                    .isEqualTo(10);

            SubmittedTask observed = submitted.get(0);
            MvcResult sse = mockMvc.perform(get("/api/tuning/tasks/" + observed.taskId + "/events")
                            .session(observed.session))
                    .andExpect(status().isOk())
                    .andExpect(request().asyncStarted())
                    .andReturn();

            long startedAt = System.nanoTime();
            queueWorker.dispatch();
            String streamingPayload = waitForSsePayload(sse, "\"receivedChars\":260", 5_000L);
            assertThat(streamingPayload).contains("event:model-stream");
            assertThat(streamingPayload).doesNotContain("event:result");
            dispatchUntilTerminal(distinctTaskIds(submitted), 20_000L);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            String eventPayload = waitForSsePayload(sse, "event:result", 5_000L);

            assertThat(eventPayload).contains("event:snapshot");
            assertThat(eventPayload).contains("event:status");
            assertThat(eventPayload).contains("event:model-stream");
            assertThat(eventPayload).contains("event:result");
            assertThat(eventPayload.indexOf("event:model-stream")).isLessThan(eventPayload.indexOf("event:result"));
            assertThat(fakeLlmClient.callCount()).isEqualTo(10);
            assertThat(elapsedMs).isLessThan(8_000L);
            assertThat(fakeLlmClient.streamCallbackCount()).isGreaterThanOrEqualTo(20);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task_stage_results WHERE stage_name = 'ANALYZE'", Integer.class))
                    .isEqualTo(10);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tuning_tasks WHERE status = 'DONE'", Integer.class))
                    .isEqualTo(10);

            for (SubmittedTask task : submitted) {
                String response = mockMvc.perform(get("/api/tuning/tasks/" + task.taskId).session(task.session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
                assertThat(response).contains("\"status\":\"DONE\"");
                assertThat(response).contains("\"summary\":\"full workflow fake result\"");
                assertThat(taskRepository.get(task.taskId).getLeaseOwner()).isNull();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void restartStyleExpiredLeaseRecoveryRequeuesFourRunningAndDrainsTwentyQueuedTasks() throws Exception {
        List<SubmittedTask> submitted = new ArrayList<SubmittedTask>();
        for (int i = 0; i < 24; i++) {
            submitted.add(submitTaskFromIndependentSession(i));
        }
        List<Long> allTaskIds = new ArrayList<Long>(distinctTaskIds(submitted));
        for (int i = 0; i < 4; i++) {
            SqlTuningTask claimed = taskRepository.claimNext("crashed-worker-" + i);
            assertThat(claimed).isNotNull();
            jdbcTemplate.update(
                    "UPDATE tuning_tasks SET lease_until = ?, updated_at = ? WHERE id = ?",
                    Timestamp.valueOf(LocalDateTime.now().minusSeconds(5)),
                    Timestamp.valueOf(LocalDateTime.now().minusSeconds(5)),
                    claimed.getId());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tuning_tasks WHERE status = 'RECEIVED'", Integer.class))
                .isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tuning_tasks WHERE status = 'QUEUED'", Integer.class))
                .isEqualTo(20);

        List<SqlTuningTask> requeued = taskRepository.requeueExpiredLeases();
        assertThat(requeued).hasSize(4);
        waitUntilCount("SELECT COUNT(*) FROM tuning_tasks WHERE status = 'QUEUED' AND lease_owner IS NULL", 24, 5_000L);

        dispatchUntilTerminal(new HashSet<Long>(allTaskIds), 40_000L);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tuning_tasks WHERE status = 'DONE'", Integer.class))
                .isEqualTo(24);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tuning_tasks WHERE lease_owner IS NOT NULL OR lease_until IS NOT NULL", Integer.class))
                .isEqualTo(0);
        assertThat(fakeLlmClient.callCount()).isEqualTo(24);
        for (Long taskId : allTaskIds) {
            SqlTuningTask task = taskRepository.get(taskId);
            assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
            assertThat(task.getAttemptCount()).isGreaterThanOrEqualTo(1);
            assertThat(task.getResult()).isNotNull();
            assertThat(task.getResult().getSummary()).isEqualTo("full workflow fake result");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task_stage_results WHERE stage_name = 'ANALYZE'", Integer.class))
                .isEqualTo(24);
    }

    private SubmittedTask submitTaskFromIndependentSession(int taskNumber) throws Exception {
        CsrfRequest csrf = csrfRequest();
        String username = usernameForTask(taskNumber);
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf.cookie)
                        .header(csrf.token.getHeaderName(), csrf.token.getToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + USER_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        CsrfRequest writeCsrf = csrfRequest();
        String body = tuningRequest(taskNumber);
        MvcResult created = mockMvc.perform(post("/api/tuning/tasks")
                        .session(session)
                        .cookie(writeCsrf.cookie)
                        .header(writeCsrf.token.getHeaderName(), writeCsrf.token.getToken())
                        .header("Idempotency-Key", "full-workflow-" + taskNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        Long taskId = JsonPathSupport.longValue(created.getResponse().getContentAsString(), "\"id\":");
        assertThat(taskId).isNotNull();
        return new SubmittedTask(taskId, session);
    }

    private String usernameForTask(int taskNumber) {
        return "user" + taskNumber;
    }

    private CsrfRequest csrfRequest() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        CsrfToken token = (CsrfToken) result.getRequest().getAttribute(CsrfToken.class.getName());
        return new CsrfRequest(token, result.getResponse().getCookie("XSRF-TOKEN"));
    }

    private String tuningRequest(int taskNumber) {
        return "{"
                + "\"dbDialect\":\"OceanBase MySQL\","
                + "\"inputType\":\"sql\","
                + "\"sqlText\":\"SELECT o.id, o.amount FROM orders o WHERE o.customer_id = " + (1000 + taskNumber) + " ORDER BY o.created_at DESC\","
                + "\"schemaText\":\"CREATE TABLE orders (id bigint primary key, customer_id bigint, amount decimal(10,2), created_at datetime);\","
                + "\"indexText\":\"KEY idx_orders_customer_created(customer_id, created_at)\","
                + "\"explainText\":\"id select_type table type key rows Extra; 1 SIMPLE orders ref idx_orders_customer_created 12 Using where\","
                + "\"tableStatsText\":\"orders rows=100000\","
                + "\"runtimeMetricsText\":\"p95=180ms\","
                + "\"allowedActions\":[\"diagnosis\",\"validation\"],"
                + "\"deepAnalysis\":false"
                + "}";
    }

    private void dispatchUntilTerminal(Set<Long> taskIds, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            queueWorker.dispatch();
            if (terminalTaskIds().containsAll(taskIds)) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Timed out waiting for terminal tasks. terminal=" + terminalTaskIds().size()
                + " expected=" + taskIds.size());
    }

    private String waitForSsePayload(MvcResult sse, String expectedEvent, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String payload = sse.getResponse().getContentAsString();
        while (System.currentTimeMillis() < deadline) {
            payload = sse.getResponse().getContentAsString();
            if (payload.contains(expectedEvent)) {
                return payload;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Timed out waiting for SSE payload containing [" + expectedEvent
                + "], actual=" + payload);
    }

    private Set<Long> terminalTaskIds() {
        return new HashSet<Long>(jdbcTemplate.queryForList(
                "SELECT id FROM tuning_tasks WHERE status IN ('DONE','FAILED')", Long.class));
    }

    private void waitUntilCount(String sql, int expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
            if (count != null && count == expected) {
                return;
            }
            Thread.sleep(50L);
        }
        Integer actual = jdbcTemplate.queryForObject(sql, Integer.class);
        throw new AssertionError("Timed out waiting for count " + expected + " from [" + sql + "], actual=" + actual);
    }

    private Set<Long> distinctTaskIds(List<SubmittedTask> submitted) {
        Set<Long> ids = new HashSet<Long>();
        for (SubmittedTask task : submitted) {
            ids.add(task.taskId);
        }
        return ids;
    }

    private Set<Integer> distinctSessionIdentities(List<SubmittedTask> submitted) {
        Set<Integer> ids = new HashSet<Integer>();
        for (SubmittedTask task : submitted) {
            ids.add(System.identityHashCode(task.session));
        }
        return ids;
    }

    private Set<Long> distinctUserIdsForSubmittedTasks(List<SubmittedTask> submitted) {
        Set<Long> ids = new HashSet<Long>();
        for (SubmittedTask task : submitted) {
            ids.add(jdbcTemplate.queryForObject(
                    "SELECT user_id FROM tuning_tasks WHERE id = ?",
                    Long.class,
                    task.taskId));
        }
        return ids;
    }

    @TestConfiguration
    static class FullWorkflowTestConfig {
        @Bean
        @Primary
        FullWorkflowStreamingFakeLlmClient fullWorkflowStreamingFakeLlmClient() {
            return new FullWorkflowStreamingFakeLlmClient();
        }

        @Bean
        @Primary
        CryptoSupport fullWorkflowCryptoSupport() {
            return new CryptoSupport();
        }

        @Bean
        @Primary
        ModelEndpointPolicy fullWorkflowModelEndpointPolicy() {
            return new ModelEndpointPolicy(false);
        }
    }

    static final class FullWorkflowStreamingFakeLlmClient implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger streamCallbacks = new AtomicInteger();

        @Override
        public LlmResponse analyze(LlmRequest request) {
            return analyze(request, null);
        }

        @Override
        public LlmResponse analyze(LlmRequest request, LlmStreamListener listener) {
            calls.incrementAndGet();
            String content = strictResult();
            if (listener != null) {
                int split = Math.min(260, content.length());
                String first = content.substring(0, split);
                listener.onContent(first, first.length());
                streamCallbacks.incrementAndGet();
            }
            simulateProviderDelay();
            if (listener != null) {
                listener.onContent(content, content.length());
                streamCallbacks.incrementAndGet();
            }
            return new LlmResponse("fake", "full-workflow-fake", content, 1L, true);
        }

        void reset() {
            calls.set(0);
            streamCallbacks.set(0);
        }

        int callCount() {
            return calls.get();
        }

        int streamCallbackCount() {
            return streamCallbacks.get();
        }

        private void simulateProviderDelay() {
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("fake LLM interrupted", e);
            }
        }

        private String strictResult() {
            return "{"
                    + "\"outcome\":\"ADVICE\","
                    + "\"summary\":\"full workflow fake result\","
                    + "\"analysisNarrative\":{\"conclusion\":\"fake streaming result\",\"sections\":[{\"kind\":\"EVIDENCE\",\"title\":\"Evidence\",\"body\":\"SQL, schema, index and explain were supplied.\",\"evidenceRefs\":[\"E_SQL\"]}]},"
                    + "\"contextAssessment\":{\"completeness\":\"SQL_SCHEMA_INDEX\",\"maxConfidence\":\"MEDIUM\",\"availableEvidence\":[\"E_SQL\"],\"missingInformation\":[\"production row variance\"],\"policyNotes\":[\"fake stream\"]},"
                    + "\"evidenceCatalog\":[{\"id\":\"E_SQL\",\"source\":\"USER_SQL\",\"summary\":\"submitted sql\",\"trustLevel\":\"HIGH\"}],"
                    + "\"diagnoses\":[{\"severity\":\"WARN\",\"title\":\"check order scan\",\"impact\":\"latency\",\"confidence\":\"LOW\",\"precondition\":\"verify in test\",\"evidenceRefs\":[\"E_SQL\"]}],"
                    + "\"rewriteCandidates\":[],"
                    + "\"indexCandidates\":[],"
                    + "\"validationPlan\":[{\"action\":\"compare explain\",\"expectedSignal\":\"rows decreases\",\"evidenceRefs\":[\"E_SQL\"]}],"
                    + "\"missingInformation\":[\"production row variance\"],"
                    + "\"safetyWarnings\":[\"fake stream output\"],"
                    + "\"review\":{\"verdict\":\"NOT_REQUESTED\",\"notes\":\"not deep\"}"
                    + "}";
        }
    }

    private static final class CsrfRequest {
        private final CsrfToken token;
        private final Cookie cookie;

        private CsrfRequest(CsrfToken token, Cookie cookie) {
            this.token = token;
            this.cookie = cookie;
        }
    }

    private static final class SubmittedTask {
        private final Long taskId;
        private final MockHttpSession session;

        private SubmittedTask(Long taskId, MockHttpSession session) {
            this.taskId = taskId;
            this.session = session;
        }
    }

    private static final class JsonPathSupport {
        private JsonPathSupport() {
        }

        private static Long longValue(String json, String field) {
            int index = json.indexOf(field);
            if (index < 0) {
                return null;
            }
            int start = index + field.length();
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
                start++;
            }
            int end = start;
            while (end < json.length() && Character.isDigit(json.charAt(end))) {
                end++;
            }
            return Long.valueOf(json.substring(start, end));
        }
    }
}
