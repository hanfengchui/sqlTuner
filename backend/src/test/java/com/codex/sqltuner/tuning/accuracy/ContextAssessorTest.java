package com.codex.sqltuner.tuning.accuracy;

import com.codex.sqltuner.rule.SqlDialect;
import com.codex.sqltuner.tuning.SqlTuningTask;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssessorTest {
    private final SqlStatementParser parser = new SqlStatementParser();
    private final ContextAssessor assessor = new ContextAssessor();

    @Test
    void readablePlanImagePlusOperationalEvidenceUnlocksDirectionsButNotDdl() {
        String sql = "SELECT * FROM (SELECT a.CUST_ID FROM GRP_CUSTOMER a "
                + "WHERE a.EC_CODE = ? AND a.BE_ID = ? "
                + "ORDER BY CREATE_TIME DESC, CUST_ID DESC) WHERE ROWNUM <= ?";
        SqlTuningTask task = new SqlTuningTask();
        task.setPlanImageFacts("readable=true\n"
                + "operators=[PHY_SORT, PHY_TABLE_SCAN]\n"
                + "tables=[A]\n"
                + "rowEstimates=[{name=A, physical_range_rows=846730}]\n"
                + "warnings=[]\n"
                + "rawTextSummary=截图显示 A 侧范围扫描后排序");
        task.setTableStatsText("GRP_CUSTOMER: 2294867 行");
        task.setRuntimeMetricsText("平均耗时: 2008ms\nCPU 占比: 41.7%");

        ContextPackage context = assessor.assess(
                task,
                parser.parse(sql, SqlDialect.OB_ORACLE),
                Collections.emptyList());

        assertThat(context.getAssessment().getCompleteness()).isEqualTo("SQL_PLAN_EVIDENCE");
        assertThat(context.getAssessment().getMaxConfidence()).isEqualTo("MEDIUM");
        assertThat(context.isAllowRewrite()).isFalse();
        assertThat(context.isAllowIndexDirection()).isTrue();
        assertThat(context.isRestrictIndexDirectionToSql()).isTrue();
        assertThat(context.isAllowIndexDdl()).isFalse();
        assertThat(context.isAllowHighConfidence()).isFalse();
        assertThat(context.getAssessment().getAvailableEvidence())
                .contains("E_SQL", "E_PLAN_IMAGE", "E_STATS", "E_RUNTIME");
        assertThat(context.getAssessment().getMissingInformation())
                .anyMatch(value -> value.contains("仅用于提高置信度") && value.contains("不阻塞方向性建议"));
    }

    @Test
    void readableFlagWithoutPlanOperatorsDoesNotUnlockDirections() {
        String sql = "SELECT id FROM orders WHERE tenant_id = ?";
        SqlTuningTask task = new SqlTuningTask();
        task.setPlanImageFacts("readable=true\noperators=[]\ntables=[]\nrowEstimates=[]\n"
                + "warnings=[]\nrawTextSummary=只识别到标题");
        task.setRuntimeMetricsText("平均耗时: 2008ms");

        ContextPackage context = assessor.assess(
                task,
                parser.parse(sql, SqlDialect.OB_MYSQL),
                Collections.emptyList());

        assertThat(context.getAssessment().getCompleteness()).isEqualTo("SQL_ONLY");
        assertThat(context.isAllowRewrite()).isFalse();
        assertThat(context.isAllowIndexDirection()).isFalse();
    }

    @Test
    void treatsSqlIdAsMetadataRatherThanRuntimeEvidence() {
        String sql = "SELECT id FROM orders WHERE tenant_id = ?";
        SqlTuningTask task = new SqlTuningTask();
        task.setRuntimeMetricsText("SQL ID: AD051FC4E9D92F153DA7EE852B4084CE");

        ContextPackage context = assessor.assess(
                task,
                parser.parse(sql, SqlDialect.OB_MYSQL),
                Collections.emptyList());

        assertThat(context.getRuntimeMetricsText()).isEmpty();
        assertThat(context.getAssessment().getAvailableEvidence()).doesNotContain("E_RUNTIME");
        assertThat(context.getAssessment().getMissingInformation())
                .anyMatch(value -> value.contains("运行指标"));
    }
}
