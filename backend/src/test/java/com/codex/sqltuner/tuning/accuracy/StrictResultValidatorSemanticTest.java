package com.codex.sqltuner.tuning.accuracy;

import com.codex.sqltuner.rule.SqlDialect;
import com.codex.sqltuner.tuning.TuningResult;
import com.codex.sqltuner.tuning.result.AnalysisNarrative;
import com.codex.sqltuner.tuning.result.ContextAssessment;
import com.codex.sqltuner.tuning.result.EvidenceItem;
import com.codex.sqltuner.tuning.result.NarrativeSection;
import com.codex.sqltuner.tuning.result.RewriteCandidate;
import com.codex.sqltuner.tuning.result.ValidationStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
}
