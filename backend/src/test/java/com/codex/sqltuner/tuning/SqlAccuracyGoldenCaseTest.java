package com.codex.sqltuner.tuning;

import com.codex.sqltuner.config.QueueProperties;
import com.codex.sqltuner.config.ModelEndpointPolicy;
import com.codex.sqltuner.llm.ConfigurableLlmClient;
import com.codex.sqltuner.llm.LlmClient;
import com.codex.sqltuner.llm.LlmProperties;
import com.codex.sqltuner.llm.LlmRequest;
import com.codex.sqltuner.llm.LlmResponse;
import com.codex.sqltuner.rule.RuleEngine;
import com.codex.sqltuner.rule.RuleFinding;
import com.codex.sqltuner.rule.SqlDialect;
import com.codex.sqltuner.rule.SqlSanitizer;
import com.codex.sqltuner.tuning.accuracy.SqlStatementParser;
import com.codex.sqltuner.tuning.accuracy.SqlStatementProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SqlAccuracyGoldenCaseTest {
    private static final String CORPUS_RESOURCE = "eval/golden-corpus.json";
    private static final String REAL_MODEL_CONFIG_RESOURCE = "eval/real-model-eval-config.json";
    private static final Set<String> CONFIDENCE_CEILINGS = setOf("LOW", "MEDIUM", "HIGH");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SqlStatementParser parser = new SqlStatementParser();
    private final SqlSanitizer sanitizer = new SqlSanitizer();
    private final RuleEngine ruleEngine = new RuleEngine();

    @Test
    void goldenCorpusCoversSixtyDistinctEvidenceGatedCases() throws Exception {
        List<JsonNode> cases = loadCases();

        assertThat(cases).filteredOn(goldenCase -> "OceanBase MySQL".equals(goldenCase.path("dialect").asText())).hasSize(30);
        assertThat(cases).filteredOn(goldenCase -> "OceanBase Oracle".equals(goldenCase.path("dialect").asText())).hasSize(30);
        assertThat(cases).extracting(goldenCase -> goldenCase.path("name").asText()).doesNotHaveDuplicates();
        assertThat(cases).extracting(goldenCase -> normalizedSql(goldenCase.path("sql").asText())).doesNotHaveDuplicates();

        for (JsonNode goldenCase : cases) {
            assertGoldenCaseContract(goldenCase);

            SqlDialect dialect = SqlDialect.from(goldenCase.path("dialect").asText());
            SqlStatementProfile profile = parser.parse(goldenCase.path("sql").asText(), dialect);
            assertThat(profile.isValid()).as(goldenCase.path("name").asText()).isTrue();
            assertThat(profile.getStatementType()).as(goldenCase.path("name").asText())
                    .isIn("SELECT", "INSERT", "UPDATE", "DELETE");
            assertThat(profile.isDdl()).as(goldenCase.path("name").asText()).isFalse();
            assertThat(profile.isMultiStatement()).as(goldenCase.path("name").asText()).isFalse();

            String sanitized = sanitizer.sanitize(goldenCase.path("sql").asText(), dialect).getSanitizedSql();
            assertThat(sanitized).as(goldenCase.path("name").asText())
                    .doesNotContain("13812345678")
                    .doesNotContain("alice@example.com")
                    .doesNotContain("6222026000000000000");

            List<RuleFinding> findings = ruleEngine.inspect(
                    sanitized,
                    textValue(goldenCase, "indexText"),
                    textValue(goldenCase, "explainText"),
                    dialect.getDisplayName());
            assertThat(findings).as(goldenCase.path("name").asText())
                    .extracting("code")
                    .contains(goldenCase.path("expectedRule").asText());
        }
    }

    @Test
    void parserRejectsDdlAndMultiStatement() {
        assertThat(parser.parse("create table t(id int)", SqlDialect.OB_MYSQL).isValid()).isFalse();
        assertThat(parser.parse("select * from t; delete from t", SqlDialect.OB_MYSQL).isValid()).isFalse();
    }

    @Test
    void realModelEvaluationConfigIsOptInBoundedAndRepairFree() throws Exception {
        JsonNode config = loadJson(REAL_MODEL_CONFIG_RESOURCE);
        RealModelEvalConfig evalConfig = RealModelEvalConfig.from(config);

        assertThat(evalConfig.enabledByDefault).isFalse();
        assertThat(evalConfig.enabled()).isFalse();
        assertThat(evalConfig.apiKeyEnv).isNotBlank();
        assertThat(evalConfig.provider).isEqualTo("openai");
        assertThat(evalConfig.baseUrl()).isNotBlank();
        assertThat(evalConfig.model()).isNotBlank();
        assertThat(evalConfig.maxStandardTasks).isEqualTo(40);
        assertThat(evalConfig.maxDeepTasks).isEqualTo(10);
        assertThat(evalConfig.maxCallsPerTask).isEqualTo(2);
        assertThat(evalConfig.maxStandardTasks + (evalConfig.maxDeepTasks * evalConfig.maxCallsPerTask))
                .isLessThanOrEqualTo(60);
        assertThat(evalConfig.repairEnabled).isFalse();

        CountingLlmClient client = new CountingLlmClient();
        RealModelEvaluationRunner runner = new RealModelEvaluationRunner(evalConfig, client);
        runner.evaluate(loadCases());
        assertThat(client.callCount()).isZero();
        assertThat(client.sawRepairPrompt()).isFalse();
    }

    @Test
    void realModelEvaluationRunnerRequiresExplicitPropertyAndApiKey() throws Exception {
        JsonNode config = loadJson(REAL_MODEL_CONFIG_RESOURCE);
        RealModelEvalConfig evalConfig = RealModelEvalConfig.from(config);
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getProperty(evalConfig.enabledProperty, "false")));
        Assumptions.assumeTrue(hasText(System.getenv(evalConfig.apiKeyEnv)));

        RealModelEvaluationRunner runner = new RealModelEvaluationRunner(evalConfig, evalConfig.realClient(objectMapper));
        runner.evaluate(loadCases());

        int expectedMaxCalls = evalConfig.maxStandardTasks + (evalConfig.maxDeepTasks * 2);
        assertThat(runner.callCount()).isLessThanOrEqualTo(expectedMaxCalls);
        assertThat(runner.maxCallsPerCase()).isLessThanOrEqualTo(evalConfig.maxCallsPerTask);
    }

    private void assertGoldenCaseContract(JsonNode goldenCase) {
        String name = goldenCase.path("name").asText();
        assertThat(name).isNotBlank();
        assertThat(goldenCase.path("dialect").asText()).as(name).isIn("OceanBase MySQL", "OceanBase Oracle");
        assertThat(goldenCase.path("sql").asText()).as(name).isNotBlank();
        assertThat(goldenCase.path("expectedRule").asText()).as(name).isNotBlank();
        assertNonBlankArray(goldenCase, "evidenceRequirements", name);
        assertNonBlankArray(goldenCase, "mustRecommendations", name);
        assertNonBlankArray(goldenCase, "forbiddenRecommendations", name);
        assertNonBlankArray(goldenCase, "semanticInvariants", name);
        assertThat(goldenCase.path("confidenceCeiling").asText()).as(name).isIn(CONFIDENCE_CEILINGS);
        JsonNode ddlGate = goldenCase.path("ddlGate");
        assertThat(ddlGate.path("allowDdl").asBoolean(true)).as(name).isFalse();
        assertThat(ddlGate.path("allowMultiStatement").asBoolean(true)).as(name).isFalse();
        Set<String> must = arrayTextSet(goldenCase.path("mustRecommendations"));
        Set<String> forbidden = arrayTextSet(goldenCase.path("forbiddenRecommendations"));
        assertThat(must).as(name).doesNotContainAnyElementsOf(forbidden);
    }

    private void assertNonBlankArray(JsonNode node, String field, String caseName) {
        JsonNode array = node.path(field);
        assertThat(array.isArray()).as(caseName + "." + field).isTrue();
        assertThat(array.size()).as(caseName + "." + field).isGreaterThan(0);
        for (JsonNode value : array) {
            assertThat(value.asText()).as(caseName + "." + field).isNotBlank();
        }
    }

    private List<JsonNode> loadCases() throws Exception {
        JsonNode root = loadJson(CORPUS_RESOURCE);
        JsonNode cases = root.path("cases");
        assertThat(cases.isArray()).isTrue();
        List<JsonNode> result = new ArrayList<JsonNode>();
        for (JsonNode item : cases) {
            result.add(item);
        }
        return result;
    }

    private JsonNode loadJson(String resource) throws Exception {
        InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        assertThat(input).as(resource).isNotNull();
        String json = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
    }

    private static String textValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static String normalizedSql(String sql) {
        return sql == null ? "" : sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static Set<String> arrayTextSet(JsonNode array) {
        Set<String> values = new HashSet<String>();
        if (array != null && array.isArray()) {
            for (JsonNode value : array) {
                values.add(value.asText());
            }
        }
        return values;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static Set<String> setOf(String... values) {
        Set<String> result = new HashSet<String>();
        for (String value : values) {
            result.add(value);
        }
        return result;
    }

    private static class RealModelEvalConfig {
        private final boolean enabledByDefault;
        private final String enabledProperty;
        private final String apiKeyEnv;
        private final String provider;
        private final String baseUrlEnv;
        private final String modelEnv;
        private final String defaultBaseUrl;
        private final String defaultModel;
        private final int maxStandardTasks;
        private final int maxDeepTasks;
        private final int maxCallsPerTask;
        private final boolean repairEnabled;

        private RealModelEvalConfig(boolean enabledByDefault,
                                    String enabledProperty,
                                    String apiKeyEnv,
                                    String provider,
                                    String baseUrlEnv,
                                    String modelEnv,
                                    String defaultBaseUrl,
                                    String defaultModel,
                                    int maxStandardTasks,
                                    int maxDeepTasks,
                                    int maxCallsPerTask,
                                    boolean repairEnabled) {
            this.enabledByDefault = enabledByDefault;
            this.enabledProperty = enabledProperty;
            this.apiKeyEnv = apiKeyEnv;
            this.provider = provider;
            this.baseUrlEnv = baseUrlEnv;
            this.modelEnv = modelEnv;
            this.defaultBaseUrl = defaultBaseUrl;
            this.defaultModel = defaultModel;
            this.maxStandardTasks = maxStandardTasks;
            this.maxDeepTasks = maxDeepTasks;
            this.maxCallsPerTask = maxCallsPerTask;
            this.repairEnabled = repairEnabled;
        }

        private static RealModelEvalConfig from(JsonNode node) {
            return new RealModelEvalConfig(
                    node.path("enabledByDefault").asBoolean(false),
                    node.path("enabledProperty").asText(),
                    node.path("apiKeyEnv").asText(),
                    node.path("provider").asText(),
                    node.path("baseUrlEnv").asText(),
                    node.path("modelEnv").asText(),
                    node.path("defaultBaseUrl").asText(),
                    node.path("defaultModel").asText(),
                    node.path("maxStandardTasks").asInt(),
                    node.path("maxDeepTasks").asInt(),
                    node.path("maxCallsPerTask").asInt(),
                    node.path("repairEnabled").asBoolean(true));
        }

        private boolean enabled() {
            return Boolean.parseBoolean(System.getProperty(enabledProperty, String.valueOf(enabledByDefault)));
        }

        private LlmClient realClient(ObjectMapper objectMapper) {
            ModelEndpointPolicy endpointPolicy = new ModelEndpointPolicy(false);
            ModelEndpointPolicy.Endpoint endpoint = endpointPolicy.normalizeBaseUrl(provider, baseUrl());
            LlmProperties properties = new LlmProperties();
            properties.setProvider(provider);
            properties.setBaseUrl(endpoint.getBaseUrl());
            properties.setModel(model());
            properties.setApiKey(System.getenv(apiKeyEnv));
            properties.setApiKeyBinding(endpoint.getApiKeyBinding());
            properties.setEndpointPolicyProduction(false);
            properties.setTextMaxTokens(2048);
            properties.setCallTimeoutMs(60000);
            QueueProperties queueProperties = new QueueProperties();
            queueProperties.setMaxRunning(1);
            return new ConfigurableLlmClient(properties, objectMapper, queueProperties);
        }

        private String baseUrl() {
            return envOrDefault(baseUrlEnv, defaultBaseUrl);
        }

        private String model() {
            return envOrDefault(modelEnv, defaultModel);
        }

        private String envOrDefault(String envName, String fallback) {
            String value = hasText(envName) ? System.getenv(envName) : null;
            return hasText(value) ? value : fallback;
        }
    }

    private static class RealModelEvaluationRunner {
        private final RealModelEvalConfig config;
        private final LlmClient llmClient;
        private final ObjectMapper objectMapper = new ObjectMapper();
        private int callCount;
        private int callsInCurrentCase;
        private int maxCallsPerCase;
        private String currentCase;

        private RealModelEvaluationRunner(RealModelEvalConfig config, LlmClient llmClient) {
            this.config = config;
            this.llmClient = llmClient;
        }

        private void evaluate(List<JsonNode> cases) {
            if (!config.enabled()) {
                return;
            }
            if (!hasText(System.getenv(config.apiKeyEnv))) {
                throw new IllegalStateException("Real model eval requires env " + config.apiKeyEnv);
            }
            int standard = 0;
            int deep = 0;
            Iterator<JsonNode> iterator = cases.iterator();
            while (iterator.hasNext() && standard < config.maxStandardTasks) {
                JsonNode goldenCase = iterator.next();
                analyze(goldenCase, false);
                standard++;
            }
            while (iterator.hasNext() && deep < config.maxDeepTasks) {
                JsonNode goldenCase = iterator.next();
                analyze(goldenCase, false);
                analyze(goldenCase, true);
                deep++;
            }
        }

        private void analyze(JsonNode goldenCase, boolean deep) {
            beforeCall(goldenCase.path("name").asText());
            String prompt = "Run single-pass evaluation for this golden SQL case.\n"
                    + "case=" + goldenCase.path("name").asText() + "\n"
                    + "sql=" + goldenCase.path("sql").asText() + "\n"
                    + "confidenceCeiling=" + goldenCase.path("confidenceCeiling").asText() + "\n"
                    + "evidenceRequirements=" + goldenCase.path("evidenceRequirements").toString() + "\n"
                    + "mustRecommendations=" + goldenCase.path("mustRecommendations").toString() + "\n"
                    + "forbiddenRecommendations=" + goldenCase.path("forbiddenRecommendations").toString() + "\n"
                    + "semanticInvariants=" + goldenCase.path("semanticInvariants").toString() + "\n"
                    + "Return only JSON with caseName, evidenceUsed, recommendations, semanticChecks, confidenceCeiling.";
            LlmResponse response = llmClient.analyze(new LlmRequest("SQL tuning golden evaluation", prompt, deep));
            validateResponse(goldenCase, response);
        }

        private void beforeCall(String caseName) {
            callCount++;
            if (!caseName.equals(currentCase)) {
                currentCase = caseName;
                callsInCurrentCase = 0;
            }
            callsInCurrentCase++;
            maxCallsPerCase = Math.max(maxCallsPerCase, callsInCurrentCase);
            if (callsInCurrentCase > config.maxCallsPerTask) {
                throw new IllegalStateException("case exceeded configured call cap: " + caseName);
            }
        }

        private void validateResponse(JsonNode goldenCase, LlmResponse response) {
            String content = response == null ? "" : response.getContent();
            if (!hasText(content)) {
                throw new IllegalStateException("model returned empty evaluation content");
            }
            try {
                JsonNode root = objectMapper.readTree(stripSingleJsonFence(content));
                if (!goldenCase.path("name").asText().equals(root.path("caseName").asText())) {
                    throw new IllegalStateException("model evaluation caseName mismatch");
                }
                requireNonEmptyArray(root, "evidenceUsed");
                requireNonEmptyArray(root, "recommendations");
                requireNonEmptyArray(root, "semanticChecks");
                if (!goldenCase.path("confidenceCeiling").asText().equals(root.path("confidenceCeiling").asText())) {
                    throw new IllegalStateException("model evaluation confidence ceiling mismatch");
                }
                String recommendations = root.path("recommendations").toString();
                for (JsonNode forbidden : goldenCase.path("forbiddenRecommendations")) {
                    if (recommendations.contains(forbidden.asText())) {
                        throw new IllegalStateException("model evaluation repeated forbidden recommendation");
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("model evaluation output failed contract validation", e);
            }
        }

        private void requireNonEmptyArray(JsonNode node, String field) {
            if (!node.path(field).isArray() || node.path(field).size() == 0) {
                throw new IllegalStateException("model evaluation missing " + field);
            }
        }

        private String stripSingleJsonFence(String content) {
            String trimmed = content == null ? "" : content.trim();
            if (trimmed.startsWith("```")) {
                int firstBreak = trimmed.indexOf('\n');
                int lastFence = trimmed.lastIndexOf("```");
                if (firstBreak >= 0 && lastFence > firstBreak) {
                    return trimmed.substring(firstBreak + 1, lastFence).trim();
                }
            }
            return trimmed;
        }

        private int callCount() {
            return callCount;
        }

        private int maxCallsPerCase() {
            return maxCallsPerCase;
        }
    }

    private static class CountingLlmClient implements LlmClient {
        private int callCount;
        private int callsInCurrentCase;
        private int maxCallsPerCase;
        private boolean sawRepairPrompt;
        private String currentCase;

        @Override
        public LlmResponse analyze(LlmRequest request) {
            callCount++;
            String prompt = request.getUserPrompt() == null ? "" : request.getUserPrompt();
            String caseName = extractCaseName(prompt);
            if (!caseName.equals(currentCase)) {
                currentCase = caseName;
                callsInCurrentCase = 0;
            }
            callsInCurrentCase++;
            maxCallsPerCase = Math.max(maxCallsPerCase, callsInCurrentCase);
            sawRepairPrompt = sawRepairPrompt || prompt.toLowerCase(Locale.ROOT).contains("repair");
            return new LlmResponse("counting", "counting", "{\"ok\":true}", 1L, true);
        }

        private int callCount() {
            return callCount;
        }

        private int maxCallsPerCase() {
            return maxCallsPerCase;
        }

        private boolean sawRepairPrompt() {
            return sawRepairPrompt;
        }

        private String extractCaseName(String prompt) {
            for (String line : prompt.split("\\n")) {
                if (line.startsWith("case=")) {
                    return line.substring("case=".length());
                }
            }
            return "";
        }
    }
}
