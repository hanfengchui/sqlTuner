package com.codex.sqltuner.tuning.accuracy;

import com.codex.sqltuner.rule.SqlDialect;
import com.codex.sqltuner.tuning.TuningResult;
import com.codex.sqltuner.tuning.result.AnalysisNarrative;
import com.codex.sqltuner.tuning.result.ContextAssessment;
import com.codex.sqltuner.tuning.result.Diagnosis;
import com.codex.sqltuner.tuning.result.EvidenceItem;
import com.codex.sqltuner.tuning.result.IndexCandidate;
import com.codex.sqltuner.tuning.result.NarrativeSection;
import com.codex.sqltuner.tuning.result.ReviewResult;
import com.codex.sqltuner.tuning.result.RewriteCandidate;
import com.codex.sqltuner.tuning.result.ValidationStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class StrictResultValidatorSemanticTest {
    private final SqlStatementParser parser = new SqlStatementParser();
    private final StrictResultValidator validator = new StrictResultValidator(parser);

    @Test
    void rejectsRewriteThatDeletesWherePredicate() {
        ValidationOutcome outcome = validateMysql(
                "SELECT * FROM orders WHERE tenant_id = 1 AND status = 'OPEN' ORDER BY created_at DESC LIMIT 10",
                "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10");

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("WHERE");
    }

    @Test
    void rejectsRewriteThatChangesOrderDirection() {
        ValidationOutcome outcome = validateMysql(
                "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10",
                "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at ASC LIMIT 10");

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("ORDER BY");
    }

    @Test
    void rejectsRewriteThatDeletesPagination() {
        ValidationOutcome outcome = validateMysql(
                "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10",
                "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC");

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("分页");
    }

    @Test
    void rejectsRewriteThatChangesLeftJoinToInnerJoin() {
        ValidationOutcome outcome = validateMysql(
                "SELECT o.id FROM orders o LEFT JOIN customers c ON o.customer_id = c.id WHERE o.tenant_id = 1",
                "SELECT o.id FROM orders o INNER JOIN customers c ON o.customer_id = c.id WHERE o.tenant_id = 1");

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("JOIN");
    }

    @Test
    void acceptsRewriteThatOnlyReordersAndConditions() {
        ValidationOutcome outcome = validateMysql(
                "SELECT * FROM orders WHERE tenant_id = 1 AND status = 'OPEN' ORDER BY created_at DESC LIMIT 10",
                "SELECT * FROM orders WHERE (status = 'OPEN') AND (tenant_id = 1) ORDER BY created_at DESC LIMIT 10");

        assertThat(outcome.isValid()).isTrue();
    }

    @Test
    void acceptsOracleOuterRownumTopN() {
        ValidationOutcome outcome = validate(
                "SELECT * FROM (SELECT o.id FROM orders o WHERE o.tenant_id = 1 ORDER BY o.created_at DESC) WHERE ROWNUM <= 10",
                "SELECT * FROM (SELECT o.id FROM orders o WHERE o.tenant_id = 1 ORDER BY o.created_at DESC) WHERE ROWNUM <= 10",
                SqlDialect.OB_ORACLE);

        assertThat(outcome.isValid()).isTrue();
    }

    @Test
    void rejectsExecutableSqlSmuggledIntoNarrative() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getAnalysisNarrative().getSections().get(0)
                .setBody("请直接执行 SELECT id FROM orders WHERE tenant_id = 1。");

        ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("analysisNarrative.sections 不得直接包含完整 SQL");
    }

    @Test
    void acceptsSafeRequestForIndexDefinitionsWithoutTreatingItAsExecutableDdl() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getMissingInformation().add("请补充 SHOW CREATE TABLE/CREATE INDEX 定义。");
        ReviewResult review = new ReviewResult();
        review.setVerdict("PASS");
        review.setNotes("已移除缺少证据的 CREATE INDEX 候选。");
        result.setReview(review);

        ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isTrue();
    }

    @Test
    void rejectsCompleteIndexDdlSmuggledIntoWarning() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getSafetyWarnings().add("请执行 CREATE INDEX idx_orders_tenant ON orders(tenant_id)。");

        ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("safetyWarnings 不得直接包含 DDL");
    }

    @Test
    void rejectsNarrativeThatExceedsConciseConversationLimit() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getAnalysisNarrative().getSections().add(section("EVIDENCE", "补充依据一"));
        result.getAnalysisNarrative().getSections().add(section("ACTION", "补充依据二"));
        result.getAnalysisNarrative().getSections().add(section("VALIDATION", "补充依据三"));
        result.getAnalysisNarrative().getSections().add(section("CAUTION", "补充依据四"));

        ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("1 至 4 个阅读块");
    }

    @Test
    void rejectsMysqlFilesortTermInOracleNarrative() {
        String sql = "SELECT * FROM (SELECT o.id FROM orders o WHERE o.tenant_id = 1 ORDER BY o.created_at DESC) WHERE ROWNUM <= 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_ORACLE);
        TuningResult result = validResult(sql);
        result.getAnalysisNarrative().getSections().get(0).setBody("请确认是否发生 filesort。");

        ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_ORACLE);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("FILESORT");
    }

    @Test
    void rejectsUnsupportedNumericPerformancePromise() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getAnalysisNarrative().getSections().get(0)
                .setBody("创建索引后预计平均耗时可降至 10ms，CPU 可降低 90%。");

        ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("无依据量化性能承诺");
    }

    @Test
    void acceptsObservedRuntimeMetricWithoutPrediction() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getAnalysisNarrative().getSections().get(0)
                .setBody("当前监控记录的平均耗时为 2008ms，应以优化后的同口径实测结果比较。");

        ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isTrue();
    }

    @Test
    void acceptsObservedPercentageWithoutFuturePromise() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getAnalysisNarrative().getSections().get(0)
                .setBody("当前监控显示索引命中率达到 90%，该数值仅作为现状基线。");

        ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isTrue();
    }

    @Test
    void acceptsNumericValidationInstructionsWithoutTreatingThemAsPerformancePromises() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getAnalysisNarrative().getSections().get(0)
                .setBody("优化后执行 10 次压测，并与当前基线按同一口径比较。");

        ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isTrue();
    }

    @Test
    void rejectsUnsupportedRowCountPromise() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getAnalysisNarrative().getSections().get(0)
                .setBody("创建索引后预计扫描行数可降低到 10 行。");

        ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("无依据量化性能承诺");
    }

    @Test
    void rejectsActualRowClaimWhenOnlyEstimateWasProvided() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getAnalysisNarrative().getSections().get(0).setBody("该查询实际返回 1 行，因此过滤选择性极高。");
        ContextPackage context = context();
        context.setExplainText("estimatedRows=1, cost=332640");

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("缺少实际行数证据");
    }

    @Test
    void rejectsUnqualifiedScanRowCountWhenOnlyEstimatedRowsWereProvided() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getAnalysisNarrative().getSections().get(0)
                .setBody("当前 SQL 执行全表扫描，扫描行数达 100000 行，资源消耗较大。");
        ContextPackage context = context();
        context.setExplainText("TABLE FULL SCAN orders; estimated rows=100000");

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("缺少实际行数证据");
    }

    @Test
    void rejectsUnqualifiedRowCountsWithoutRepeatedUnitSuffix() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        String[] claims = {
                "当前扫描行数达 100000。",
                "当前返回行数为 1。",
                "The query returned rows: 100000."
        };
        for (String claim : claims) {
            SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
            TuningResult result = validResult(sql);
            result.getAnalysisNarrative().getSections().get(0).setBody(claim);

            ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

            assertThat(outcome.isValid()).as(claim).isFalse();
            assertThat(outcome.summary()).as(claim).contains("缺少实际行数证据");
        }
    }

    @Test
    void doesNotBorrowEstimateQualifierFromAnEarlierClause() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        String[] claims = {
                "执行计划估计行数为 10；但当前扫描行数达 100000。",
                "The plan estimated rows at 10. The query scanned 100000 rows."
        };
        for (String claim : claims) {
            SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
            TuningResult result = validResult(sql);
            result.getAnalysisNarrative().getSections().get(0).setBody(claim);

            ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

            assertThat(outcome.isValid()).as(claim).isFalse();
            assertThat(outcome.summary()).as(claim).contains("缺少实际行数证据");
        }
    }

    @Test
    void bindsEachQualifierToItsOwnRowCountMention() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 100";
        String[] claims = {
                "LIMIT 100 最多返回 100 行，但扫描行数达 100000 行。",
                "Estimated returned rows: 100, but the query scanned 100000 rows."
        };
        for (String claim : claims) {
            SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
            TuningResult result = validResult(sql);
            result.getAnalysisNarrative().getSections().get(0).setBody(claim);

            ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

            assertThat(outcome.isValid()).as(claim).isFalse();
            assertThat(outcome.summary()).as(claim).contains("缺少实际行数证据");
        }
    }

    @Test
    void bindsEstimateQualifierToGenericEstimatedRowReference() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 100";
        String[] claims = {
                "计划估计行数为 10 而扫描行数达 100000 行。",
                "Estimated rows: 10 versus scanned rows: 100000."
        };
        for (String claim : claims) {
            SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
            TuningResult result = validResult(sql);
            result.getAnalysisNarrative().getSections().get(0).setBody(claim);

            ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

            assertThat(outcome.isValid()).as(claim).isFalse();
            assertThat(outcome.summary()).as(claim).contains("缺少实际行数证据");
        }
    }

    @Test
    void ignoresNegatedEstimateAndUpperBoundQualifiers() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 100";
        String[] claims = {
                "这不是估算值而是扫描行数达 100000 行。",
                "扫描行数达 100000 行并不是估算值。",
                "返回行数达 100000 行且不受上限限制。",
                "Scanned rows: 100000, not estimated."
        };
        for (String claim : claims) {
            SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
            TuningResult result = validResult(sql);
            result.getAnalysisNarrative().getSections().get(0).setBody(claim);

            ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

            assertThat(outcome.isValid()).as(claim).isFalse();
            assertThat(outcome.summary()).as(claim).contains("缺少实际行数证据");
        }
    }

    @Test
    void acceptsExplicitlyEstimatedScanRowCount() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getAnalysisNarrative().getSections().get(0)
                .setBody("执行计划估计扫描行数为 100000 行，该数值不是实际返回行数。");
        ContextPackage context = context();
        context.setExplainText("TABLE FULL SCAN orders; estimated rows=100000");

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isTrue();
    }

    @Test
    void acceptsEstimateSynonymsAndEnglishEstimatedRows() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        String[] claims = {
                "执行计划估算扫描行数为 100000 行。",
                "The optimizer estimated scanned rows: 100000."
        };
        for (String claim : claims) {
            SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
            TuningResult result = validResult(sql);
            result.getAnalysisNarrative().getSections().get(0).setBody(claim);

            ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

            assertThat(outcome.isValid()).as(claim).isTrue();
        }
    }

    @Test
    void acceptsEstimateQualifierAfterTheRowCountMention() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        String[] claims = {
                "扫描行数为 100000 行（估算值）。",
                "Scanned rows: 100k (estimated)."
        };
        for (String claim : claims) {
            SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
            TuningResult result = validResult(sql);
            result.getAnalysisNarrative().getSections().get(0).setBody(claim);

            ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

            assertThat(outcome.isValid()).as(claim).isTrue();
        }
    }

    @Test
    void acceptsLeadingEstimatePhraseAcrossComma() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        String[] claims = {
                "根据执行计划估算，扫描行数为 100000 行。",
                "Estimated by the optimizer, scanned rows: 100000."
        };
        for (String claim : claims) {
            SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
            TuningResult result = validResult(sql);
            result.getAnalysisNarrative().getSections().get(0).setBody(claim);

            ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

            assertThat(outcome.isValid()).as(claim).isTrue();
        }
    }

    @Test
    void acceptsEachPlanEstimateWhenMultipleQualifiedCountsShareOneClause() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        Diagnosis diagnosis = new Diagnosis();
        diagnosis.setSeverity("HIGH");
        diagnosis.setTitle("主表扫描范围过大");
        diagnosis.setImpact("计划对 A 做 PHY_TABLE_SCAN，计划估计扫描约 16 万行、计划估计输出约 1 万行更新候选，读成本集中在主表。");
        diagnosis.setConfidence("MEDIUM");
        diagnosis.setPrecondition("文本执行计划显示主表全表扫描。");
        diagnosis.setEvidenceRefs(Collections.singletonList("E1"));
        result.setDiagnoses(Collections.singletonList(diagnosis));

        ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isTrue();
    }

    @Test
    void acceptsPlanEstimateAfterAnUnrelatedTableStatisticInTheSameClause() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        Diagnosis diagnosis = new Diagnosis();
        diagnosis.setSeverity("INFO");
        diagnosis.setTitle("配置表扫描成本次要");
        diagnosis.setImpact("配置表表统计约 1212 行，计划估计全表扫描并分组后约 15 行参与 HASH JOIN，相对主表扫描不是优先优化点。");
        diagnosis.setConfidence("MEDIUM");
        diagnosis.setPrecondition("执行计划显示配置表全表扫描后分组。");
        diagnosis.setEvidenceRefs(Collections.singletonList("E1"));
        result.setDiagnoses(Collections.singletonList(diagnosis));

        ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isTrue();
    }

    @Test
    void acceptsSqlRowLimitAsAnUpperBoundRatherThanActualRuntimeCount() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 100";
        String[] claims = {
                "LIMIT 100 表示最多返回 100 行。",
                "ROWNUM 条件限制返回行数不超过 100。",
                "The LIMIT clause caps returned rows at most 100."
        };
        for (String claim : claims) {
            SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
            TuningResult result = validResult(sql);
            result.getAnalysisNarrative().getSections().get(0).setBody(claim);

            ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

            assertThat(outcome.isValid()).as(claim).isTrue();
        }
    }

    @Test
    void acceptsUnqualifiedScanRowCountWhenMatchingActualEvidenceExists() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getAnalysisNarrative().getSections().get(0)
                .setBody("监控显示本次扫描行数为 100000 行。");
        ContextPackage context = context();
        context.setRuntimeMetricsText("实际扫描行数: 100000");

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isTrue();
    }

    @Test
    void normalizesCompactRowCountsAgainstActualEvidence() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getAnalysisNarrative().getSections().get(0)
                .setBody("监控显示本次扫描行数为 10 万。");
        ContextPackage context = context();
        context.setRuntimeMetricsText("实际扫描行数: 100000");

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isTrue();
    }

    @Test
    void rejectsCommonActualRowClaimVariantsWithoutRuntimeEvidence() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        String[] claims = {
                "该查询实际只返回 1 行。",
                "该查询实际结果只有 1 行。",
                "该查询真实结果为 1 行。"
        };
        for (String claim : claims) {
            SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
            TuningResult result = validResult(sql);
            result.getAnalysisNarrative().getSections().get(0).setBody(claim);

            ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

            assertThat(outcome.isValid()).as(claim).isFalse();
            assertThat(outcome.summary()).as(claim).contains("缺少实际行数证据");
        }
    }

    @Test
    void acceptsActualRowClaimWhenRuntimeEvidenceWasProvided() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getAnalysisNarrative().getSections().get(0).setBody("该查询实际返回 1 行，当前过滤选择性较高。");
        ContextPackage context = context();
        context.setRuntimeMetricsText("实际返回行数: 1");

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isTrue();
    }

    @Test
    void rejectsActualRowClaimWhenEvidenceHasNoNumericValue() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getAnalysisNarrative().getSections().get(0).setBody("该查询实际返回 1 行。");
        ContextPackage context = context();
        context.setExplainText("actual rows unavailable");

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("缺少实际行数证据");
    }

    @Test
    void rejectsActualRowClaimWhenEvidenceContainsDifferentRowCount() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getAnalysisNarrative().getSections().get(0).setBody("该查询实际返回 1 行。");
        ContextPackage context = context();
        context.setRuntimeMetricsText("实际返回行数: 99");

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("缺少实际行数证据");
    }

    @Test
    void rejectsMultipleActualRowClaimsWhenOnlyOneHasEvidence() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getAnalysisNarrative().getSections().get(0)
                .setBody("该查询实际返回 1 行，但实际扫描 2,294,758 行。");
        ContextPackage context = context();
        context.setRuntimeMetricsText("实际返回行数: 1");

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("缺少实际行数证据");
    }

    @Test
    void rejectsIndexCandidateCoveredByExistingIndexPrefix() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.setIndexCandidates(new ArrayList<IndexCandidate>(Collections.singletonList(
                indexCandidate("orders", "tenant_id"))));
        ContextPackage context = context();
        context.setIndexText("CREATE INDEX idx_orders_existing ON orders(tenant_id, created_at DESC)");

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("现有索引前缀重复");
    }

    @Test
    void rejectsIndexCandidateCoveredByShowIndexRows() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.setIndexCandidates(new ArrayList<IndexCandidate>(Collections.singletonList(
                indexCandidate("orders", "tenant_id", "created_at DESC"))));
        ContextPackage context = context();
        context.setIndexText("SHOW INDEX FROM orders;\n"
                + "| Table | Non_unique | Key_name | Seq_in_index | Column_name | Collation |\n"
                + "| orders | 1 | idx_orders_tenant_created | 1 | tenant_id | A |\n"
                + "| orders | 1 | idx_orders_tenant_created | 2 | created_at | D |");

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("现有索引前缀重复");
    }

    @Test
    void acceptsIndexCandidateWithDifferentSecondColumn() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 AND status = 'OPEN' ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.setIndexCandidates(new ArrayList<IndexCandidate>(Collections.singletonList(
                indexCandidate("orders", "tenant_id", "status"))));
        ContextPackage context = context();
        context.setIndexText("CREATE INDEX idx_orders_existing ON orders(tenant_id, created_at DESC)");

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isTrue();
    }

    @Test
    void rejectsIndexDdlThatDisagreesWithCandidateMetadata() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        IndexCandidate candidate = indexCandidate("orders", "created_at");
        candidate.setDdl("CREATE INDEX idx_orders_tenant ON orders(tenant_id)");
        result.setIndexCandidates(new ArrayList<IndexCandidate>(Collections.singletonList(candidate)));

        ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("DDL 与 tableName/columnOrder 不一致");
    }

    @Test
    void rejectsIndexCandidateCoveredByInlinePrimaryKey() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.setIndexCandidates(new ArrayList<IndexCandidate>(Collections.singletonList(
                indexCandidate("orders", "tenant_id"))));
        ContextPackage context = context();
        context.setIndexText("CREATE TABLE orders (id BIGINT, tenant_id BIGINT, created_at TIMESTAMP, PRIMARY KEY (tenant_id, created_at))");

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("现有索引前缀重复");
    }

    @Test
    void rejectsIndexCandidateCoveredByInlineUniqueKey() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.setIndexCandidates(new ArrayList<IndexCandidate>(Collections.singletonList(
                indexCandidate("orders", "tenant_id"))));
        ContextPackage context = context();
        context.setIndexText("CREATE TABLE orders (id BIGINT, tenant_id BIGINT, created_at TIMESTAMP, UNIQUE KEY uk_orders_tenant (tenant_id, created_at))");

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("现有索引前缀重复");
    }

    @Test
    void doesNotTreatForeignKeyConstraintAsExistingIndex() {
        String sql = "SELECT * FROM child_orders WHERE parent_id = 1";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_ORACLE);
        TuningResult result = validResult(sql);
        result.setIndexCandidates(new ArrayList<IndexCandidate>(Collections.singletonList(
                indexCandidate("child_orders", "parent_id"))));
        ContextPackage context = context();
        context.setIndexText("CREATE TABLE child_orders (id NUMBER, parent_id NUMBER, "
                + "CONSTRAINT fk_child_parent FOREIGN KEY (parent_id) REFERENCES parent_orders(id))");

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_ORACLE);

        assertThat(outcome.isValid()).isTrue();
    }

    @Test
    void rejectsIndexDdlWithDirectionDifferentFromCandidateMetadata() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        IndexCandidate candidate = indexCandidate("orders", "tenant_id ASC", "created_at DESC");
        candidate.setDdl("CREATE INDEX idx_orders_sort ON orders(tenant_id ASC, created_at ASC)");
        result.setIndexCandidates(new ArrayList<IndexCandidate>(Collections.singletonList(candidate)));

        ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("DDL 与 tableName/columnOrder 不一致");
    }

    @Test
    void acceptsSqlBoundIndexDirectionWithoutDdlForPlanEvidenceMode() {
        String sql = "SELECT * FROM (SELECT a.CUST_ID FROM GRP_CUSTOMER a "
                + "WHERE a.EC_CODE = 1 AND a.BE_ID = 2 "
                + "ORDER BY CREATE_TIME DESC, CUST_ID DESC) WHERE ROWNUM <= 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_ORACLE);
        assertThat(profile.getIndexRelevantColumns())
                .contains("ec_code", "be_id", "create_time", "cust_id");
        TuningResult result = validResult(sql);
        result.setIndexCandidates(new ArrayList<IndexCandidate>(Collections.singletonList(
                indexCandidate("GRP_CUSTOMER", "EC_CODE", "BE_ID", "CREATE_TIME DESC", "CUST_ID DESC"))));
        ContextPackage context = context();
        context.setAllowIndexDdl(false);
        context.setAllowHighConfidence(false);
        context.setRestrictIndexDirectionToSql(true);

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_ORACLE);

        assertThat(outcome.isValid()).isTrue();
        assertThat(result.getIndexCandidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.getDdl()).isNullOrEmpty();
            assertThat(candidate.getConfidence()).isEqualTo("MEDIUM");
        });
    }

    @Test
    void capsDiagnosisAndIndexConfidenceAtContextAssessmentMaximum() {
        String sql = "SELECT id FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);

        Diagnosis diagnosis = new Diagnosis();
        diagnosis.setSeverity("WARN");
        diagnosis.setTitle("需要核验的访问路径风险");
        diagnosis.setImpact("可能产生额外扫描");
        diagnosis.setConfidence("MEDIUM");
        diagnosis.setPrecondition("需要执行计划确认");
        diagnosis.setEvidenceRefs(Collections.singletonList("E1"));
        result.setDiagnoses(new ArrayList<Diagnosis>(Collections.singletonList(diagnosis)));

        IndexCandidate candidate = indexCandidate("orders", "tenant_id", "created_at DESC");
        candidate.setConfidence("HIGH");
        result.setIndexCandidates(new ArrayList<IndexCandidate>(Collections.singletonList(candidate)));

        ContextPackage context = context();
        ContextAssessment assessment = new ContextAssessment();
        assessment.setMaxConfidence("LOW");
        context.setAssessment(assessment);
        context.setAllowHighConfidence(false);

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isTrue();
        assertThat(result.getDiagnoses()).singleElement()
                .satisfies(value -> assertThat(value.getConfidence()).isEqualTo("LOW"));
        assertThat(result.getIndexCandidates()).singleElement()
                .satisfies(value -> assertThat(value.getConfidence()).isEqualTo("LOW"));
    }

    @Test
    void rejectsPlanEvidenceIndexDirectionThatInventsAColumn() {
        String sql = "SELECT id FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.setIndexCandidates(new ArrayList<IndexCandidate>(Collections.singletonList(
                indexCandidate("orders", "tenant_id", "invented_column"))));
        ContextPackage context = context();
        context.setAllowIndexDdl(false);
        context.setAllowHighConfidence(false);
        context.setRestrictIndexDirectionToSql(true);

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary()).contains("原 SQL").contains("invented_column");
    }

    @Test
    void acceptsCandidateThatExtendsInlinePrimaryKey() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 AND status = 'OPEN' ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.setIndexCandidates(new ArrayList<IndexCandidate>(Collections.singletonList(
                indexCandidate("orders", "tenant_id", "status"))));
        ContextPackage context = context();
        context.setIndexText("CREATE TABLE orders (id BIGINT, tenant_id BIGINT, status VARCHAR(32), PRIMARY KEY (tenant_id))");

        ValidationOutcome outcome = validator.validate(result, context, profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isTrue();
    }

    @Test
    void rejectsClaimsHiddenInWarningsReviewAndEvidenceSummary() {
        String sql = "SELECT * FROM orders WHERE tenant_id = 1 ORDER BY created_at DESC LIMIT 10";
        SqlStatementProfile profile = parser.parse(sql, SqlDialect.OB_MYSQL);
        TuningResult result = validResult(sql);
        result.getSafetyWarnings().add("该方案预计耗时降至 10ms。");
        ReviewResult review = new ReviewResult();
        review.setVerdict("PASS");
        review.setNotes("预计 CPU 可降低 90%。");
        result.setReview(review);
        result.getEvidenceCatalog().get(0).setSummary("预计逻辑读降到十位数。");

        ValidationOutcome outcome = validator.validate(result, context(), profile, SqlDialect.OB_MYSQL);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.summary())
                .contains("safetyWarnings 包含无依据量化性能承诺")
                .contains("review 包含无依据量化性能承诺")
                .contains("evidenceCatalog 包含无依据量化性能承诺");
    }

    private ValidationOutcome validateMysql(String originalSql, String rewriteSql) {
        return validate(originalSql, rewriteSql, SqlDialect.OB_MYSQL);
    }

    private ValidationOutcome validate(String originalSql, String rewriteSql, SqlDialect dialect) {
        SqlStatementProfile originalProfile = parser.parse(originalSql, dialect);
        assertThat(originalProfile.isValid()).as(originalProfile.getReason()).isTrue();
        TuningResult result = validResult(rewriteSql);
        return validator.validate(result, context(), originalProfile, dialect);
    }

    private TuningResult validResult(String rewriteSql) {
        TuningResult result = new TuningResult();
        result.setOutcome("ADVICE");
        result.setContextAssessment(new ContextAssessment());
        result.setEvidenceCatalog(Collections.singletonList(evidence()));
        result.setAnalysisNarrative(narrative());
        result.setRewriteCandidates(new ArrayList<RewriteCandidate>(Collections.singletonList(rewrite(rewriteSql))));
        result.setValidationPlan(new ArrayList<ValidationStep>(Collections.singletonList(validationStep())));
        return result;
    }

    private ContextPackage context() {
        ContextPackage context = new ContextPackage();
        context.setAllowRewrite(true);
        context.setAllowIndexDirection(true);
        context.setAllowIndexDdl(true);
        context.setAllowHighConfidence(true);
        return context;
    }

    private EvidenceItem evidence() {
        EvidenceItem item = new EvidenceItem();
        item.setId("E1");
        item.setSource("SQL");
        item.setSummary("SQL text");
        item.setTrustLevel("HIGH");
        return item;
    }

    private RewriteCandidate rewrite(String sql) {
        RewriteCandidate candidate = new RewriteCandidate();
        candidate.setSql(sql);
        candidate.setEvidenceRefs(Collections.singletonList("E1"));
        return candidate;
    }

    private AnalysisNarrative narrative() {
        NarrativeSection section = section("EVIDENCE", "可验证依据");

        AnalysisNarrative narrative = new AnalysisNarrative();
        narrative.setConclusion("该候选仅用于验证语义守卫是否允许等价改写。");
        narrative.setSections(new ArrayList<NarrativeSection>(Collections.singletonList(section)));
        return narrative;
    }

    private NarrativeSection section(String kind, String title) {
        NarrativeSection section = new NarrativeSection();
        section.setKind(kind);
        section.setTitle(title);
        section.setBody("改写候选必须保持原 SQL 的业务语义。");
        section.setEvidenceRefs(Collections.singletonList("E1"));
        return section;
    }

    private ValidationStep validationStep() {
        ValidationStep step = new ValidationStep();
        step.setAction("Run EXPLAIN");
        step.setEvidenceRefs(Collections.singletonList("E1"));
        return step;
    }

    private IndexCandidate indexCandidate(String tableName, String... columns) {
        IndexCandidate candidate = new IndexCandidate();
        candidate.setTableName(tableName);
        candidate.setColumnOrder(Arrays.asList(columns));
        candidate.setBenefit("减少不必要的扫描");
        candidate.setWriteCost("需要评估写入成本");
        candidate.setRisk("需验证现有索引覆盖关系");
        candidate.setValidation("比较优化前后执行计划");
        candidate.setConfidence("MEDIUM");
        candidate.setEvidenceRefs(Collections.singletonList("E1"));
        return candidate;
    }
}
