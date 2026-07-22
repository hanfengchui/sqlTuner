package com.codex.sqltuner.tuning.input;

import com.codex.sqltuner.rule.SqlDialect;
import com.codex.sqltuner.tuning.SqlTuningTask;
import com.codex.sqltuner.tuning.accuracy.ContextAssessor;
import com.codex.sqltuner.tuning.accuracy.ContextPackage;
import com.codex.sqltuner.tuning.accuracy.SqlStatementParser;
import com.codex.sqltuner.tuning.accuracy.SqlStatementProfile;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportTextParserTest {
    private final ReportTextParser parser = new ReportTextParser();

    @Test
    void parsesTheSampleReportWithoutTreatingHistoricalClaimsAsEvidence() {
        String report = "SQL ID: B05FC9141039983E7E33ECD3A563E37D "
                + "SQL: select * from ( select a.ACCOUNT_OID, a.BE_ID, a.CUST_ID, a.CREATE_TIME, "
                + "b.ADDRESS, b.\"PASSWORD\" from GRP_CUSTOMER a left join GRP_CUSTOMER_EX b "
                + "on a.BE_ID = b.BE_ID and a.CUST_ID = b.CUST_ID "
                + "where ((a.EC_CODE = ? and a.BE_ID = ? )) ORDER BY CREATE_TIME DESC,CUST_ID DESC ) "
                + "where rownum <= ?\n"
                + "执行次数: 21.88\n"
                + "CPU占比: 41.7%\n"
                + "平均耗时: 2008ms\n"
                + "根因: 执行计划异常：驱动表GRP_CUSTOMER全表扫描（FULL），且使用Nested Loop连接导致大量随机IO，"
                + "平均返回行数仅1行但耗时高达2008ms，存在严重扫描放大。 "
                + "限流值: 0 优化建议: 1. 创建复合索引：CREATE INDEX idx_grp_customer_ec_be "
                + "ON GRP_CUSTOMER(EC_CODE, BE_ID, CREATE_TIME DESC, CUST_ID DESC); "
                + "2. 建议将外层ROWNUM过滤下推到子查询内部。\n"
                + "执行计划\n\n"
                + "SELECT * FROM GRP_CUSTOMER;          2294867\n"
                + "SELECT * FROM GRP_CUSTOMER_EX;      2294758";

        ParsedReport result = parser.parse(report);

        assertThat(result.getExtractedSql())
                .startsWith("select * from")
                .contains("GRP_CUSTOMER_EX", "where rownum <= ?")
                .doesNotContain("执行次数");
        assertThat(result.getInferredDialect()).isEqualTo("OceanBase Oracle");
        assertThat(result.getRuntimeMetricsText()).isEqualTo(
                "SQL ID: B05FC9141039983E7E33ECD3A563E37D\n"
                        + "执行次数: 21.88\n"
                        + "CPU 占比: 41.7%\n"
                        + "平均耗时: 2008ms\n"
                        + "平均返回行数（报告结论内识别，待核验）: 1 行\n"
                        + "限流值（报告结论内识别，待核验）: 0");
        assertThat(result.getTableStatsText()).isEqualTo(
                "GRP_CUSTOMER: 2294867 行\nGRP_CUSTOMER_EX: 2294758 行");
        assertThat(result.getPriorAnalysisText())
                .contains("历史根因（待核验，不作为事实证据）")
                .contains("执行计划异常")
                .contains("历史优化建议（待核验，不作为事实证据）")
                .contains("CREATE INDEX idx_grp_customer_ec_be");
        assertThat(result.getExplainText()).isEmpty();
        assertThat(result.getSchemaText()).isEmpty();
        assertThat(result.getIndexText()).isEmpty();
        assertThat(result.getObVersion()).isEmpty();
        assertThat(result.getWarnings()).anyMatch(warning -> warning.contains("仅包含表行数查询"));

        SqlTuningTask task = new SqlTuningTask();
        task.setRuntimeMetricsText(result.getRuntimeMetricsText());
        SqlStatementProfile profile = new SqlStatementParser().parse(result.getExtractedSql(), SqlDialect.OB_ORACLE);
        ContextPackage context = new ContextAssessor().assess(task, profile, Collections.emptyList());
        assertThat(context.getRuntimeMetricsText())
                .contains("执行次数: 21.88", "平均耗时: 2008ms")
                .doesNotContain("平均返回行数", "限流值", ReportTextParser.UNVERIFIED_REPORT_METRIC_MARKER);
        assertThat(context.getAssessment().getAvailableEvidence()).contains("E_RUNTIME");
        assertThat(context.getAssessment().getPolicyNotes())
                .anyMatch(note -> note.contains("未计入 E_RUNTIME"));
    }

    @Test
    void extractsExplicitEvidenceSectionsAndUnlocksDdlOnlyWhenTheyAreComplete() {
        String report = "SQL: SELECT id, created_at FROM orders "
                + "WHERE tenant_id = ? AND status = ? ORDER BY created_at DESC\n"
                + "OceanBase 版本: 4.3.2.1\n"
                + "表结构:\n"
                + "CREATE TABLE orders (\n"
                + "  id BIGINT NOT NULL,\n"
                + "  tenant_id BIGINT NOT NULL,\n"
                + "  status VARCHAR(16) NOT NULL,\n"
                + "  created_at TIMESTAMP NOT NULL,\n"
                + "  PRIMARY KEY (id)\n"
                + ");\n"
                + "现有索引:\n"
                + "CREATE INDEX idx_orders_tenant ON orders(tenant_id);\n"
                + "执行计划:\n"
                + "| ID | OPERATOR | NAME | EST. ROWS | COST |\n"
                + "| 0 | TABLE FULL SCAN | orders | 2300000 | 90321 |\n"
                + "表统计:\n"
                + "orders: 2300000 行，status NDV: 8\n";

        ParsedReport result = parser.parse(report);

        assertThat(result.getExtractedSql()).startsWith("SELECT id, created_at FROM orders");
        assertThat(result.getSchemaText()).contains("CREATE TABLE orders", "PRIMARY KEY");
        assertThat(result.getIndexText()).contains("CREATE INDEX idx_orders_tenant");
        assertThat(result.getExplainText()).contains("TABLE FULL SCAN", "EST. ROWS");
        assertThat(result.getTableStatsText()).contains("orders: 2300000 行", "status NDV: 8");
        assertThat(result.getObVersion()).isEqualTo("4.3.2.1");

        SqlTuningTask task = new SqlTuningTask();
        task.setSchemaText(result.getSchemaText());
        task.setIndexText(result.getIndexText());
        task.setExplainText(result.getExplainText());
        task.setTableStatsText(result.getTableStatsText());
        task.setObVersion(result.getObVersion());
        SqlStatementProfile profile = new SqlStatementParser().parse(result.getExtractedSql(), SqlDialect.OB_MYSQL);
        ContextPackage context = new ContextAssessor().assess(task, profile, Collections.emptyList());

        assertThat(context.getAssessment().getCompleteness()).isEqualTo("FULL");
        assertThat(context.isAllowIndexDdl()).isTrue();
        assertThat(context.isAllowHighConfidence()).isTrue();
    }

    @Test
    void parsesTheCopyPasteTemplateIncludingBusinessConstraintsAndAllowedActions() {
        String report = "数据库方言: OceanBase Oracle\n"
                + "SQL ID: AD051FC4E9D92F153DA7EE852B4084CE\n"
                + "SQL:\n"
                + "UPDATE OPENAPI_FQLS_SALE_SYNC_MID A\n"
                + "SET F_SYNC_STATUS = ?\n"
                + "WHERE A.F_STATUS = ? AND A.F_IS_ZDGS = ?;\n"
                + "运行指标:\n"
                + "执行次数: 21\n"
                + "平均耗时: 2008ms\n"
                + "平均返回行数: 10582\n"
                + "逻辑读: 2400000\n"
                + "物理读: 18300\n"
                + "OceanBase 版本: 4.3.5.1\n"
                + "表统计:\n"
                + "OPENAPI_FQLS_SALE_SYNC_MID: 122880 行\n"
                + "筛选条件实际命中: 10582 行\n"
                + "表结构:\n"
                + "CREATE TABLE OPENAPI_FQLS_SALE_SYNC_MID (F_STATUS NUMBER(1), F_IS_ZDGS NUMBER(1), F_PROV_NUM VARCHAR2(21));\n"
                + "现有索引:\n"
                + "无（已核实：无主键、唯一约束和二级索引）\n"
                + "执行计划:\n"
                + "| ID | OPERATOR | NAME | EST. ROWS | COST |\n"
                + "| 0 | PHY_TABLE_SCAN | A | 10582 | 65821 |\n"
                + "业务语义约束:\n"
                + "- 只允许更新 F_SYNC_STATUS，不能改变筛选条件或更新范围。\n"
                + "- 空配置时必须保留默认值 8。\n"
                + "允许建议类型: 诊断、索引、验证";

        ParsedReport result = parser.parse(report);

        assertThat(result.getExtractedSql())
                .contains("UPDATE OPENAPI_FQLS_SALE_SYNC_MID A", "SET F_SYNC_STATUS = ?", "WHERE A.F_STATUS = ? AND A.F_IS_ZDGS = ?;")
                .doesNotContain("运行指标", "业务语义约束");
        assertThat(result.getRuntimeMetricsText()).contains("执行次数: 21", "平均耗时: 2008ms", "逻辑读: 2400000");
        assertThat(result.getTableStatsText()).contains("122880 行", "筛选条件实际命中: 10582 行");
        assertThat(result.getIndexText()).isEqualTo("无（已核实：无主键、唯一约束和二级索引）");
        assertThat(result.getExplainText()).contains("PHY_TABLE_SCAN", "65821");
        assertThat(result.getBusinessInvariants()).contains("只允许更新 F_SYNC_STATUS", "空配置时必须保留默认值 8");
        assertThat(result.getAllowedActions()).containsExactly("diagnosis", "index", "validation");

        ParsedReport allActions = parser.parse("SQL: SELECT id FROM orders WHERE tenant_id = ?\n"
                + "允许建议类型: 全部");
        assertThat(allActions.getAllowedActions()).containsExactly("diagnosis", "index", "rewrite", "validation");
    }

    @Test
    void keepsARealTextExecutionPlan() {
        String report = "SQL: SELECT o.id FROM orders o WHERE o.customer_id = ?\n"
                + "执行次数: 20\n"
                + "执行计划\n"
                + "| ID | OPERATOR   | NAME             | EST.ROWS | COST |\n"
                + "| 0  | TABLE SCAN | idx_customer_id  | 1        | 4    |";

        ParsedReport result = parser.parse(report);

        assertThat(result.getExplainText()).contains("OPERATOR", "TABLE SCAN", "idx_customer_id");
        assertThat(result.getTableStatsText()).isEmpty();
        assertThat(result.getWarnings()).noneMatch(warning -> warning.contains("补充文本 EXPLAIN"));
    }

    @Test
    void keepsAStandardMysqlTabularExplainWithTypeAndExtraColumns() {
        String report = "SQL: SELECT id FROM orders WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 50\n"
                + "EXPLAIN:\n"
                + "| id | select_type | table | type | possible_keys | key | rows | Extra |\n"
                + "| 1 | SIMPLE | orders | ALL | idx_tenant | NULL | 12000000 | Using where; Using filesort |";

        ParsedReport result = parser.parse(report);

        assertThat(result.getExplainText())
                .contains("select_type", "possible_keys", "ALL", "Using filesort");
        assertThat(result.getWarnings()).noneMatch(warning -> warning.contains("未包含可识别的计划算子"));
    }

    @Test
    void recognizesReturnedRowsAndIoMetricsWithoutPromotingHistoricalClaimsToEvidence() {
        String report = "SQL: SELECT id FROM orders WHERE tenant_id = ?\n"
                + "平均耗时: 2008ms\n"
                + "根因: 平均返回行数：仅1行，但扫描放大。逻辑读: 约123,456 次，physical reads = 789。\n"
                + "限流值: 0";

        ParsedReport result = parser.parse(report);

        assertThat(result.getRuntimeMetricsText())
                .contains("平均耗时: 2008ms")
                .contains("平均返回行数（报告结论内识别，待核验）: 1 行")
                .contains("逻辑读（报告结论内识别，待核验）: 123456 次")
                .contains("物理读（报告结论内识别，待核验）: 789");
        assertThat(result.getPriorAnalysisText())
                .contains("平均返回行数：仅1行")
                .contains("逻辑读: 约123,456 次")
                .contains("physical reads = 789");

        SqlTuningTask task = new SqlTuningTask();
        task.setRuntimeMetricsText(result.getRuntimeMetricsText());
        SqlStatementProfile profile = new SqlStatementParser().parse(result.getExtractedSql(), SqlDialect.OB_MYSQL);
        ContextPackage context = new ContextAssessor().assess(task, profile, Collections.emptyList());
        assertThat(context.getRuntimeMetricsText()).isEqualTo("平均耗时: 2008ms");
        assertThat(context.getAssessment().getAvailableEvidence()).contains("E_RUNTIME");
        assertThat(context.getAssessment().getPolicyNotes())
                .anyMatch(note -> note.contains("界面回执和待核验背景"));
    }

    @Test
    void doesNotCutSqlAtMetricWordsInsideAStringLiteral() {
        String report = "SQL: SELECT 'logical reads: 123' AS metric FROM orders WHERE id = ?\n"
                + "平均耗时: 1ms";

        ParsedReport result = parser.parse(report);

        assertThat(result.getExtractedSql())
                .isEqualTo("SELECT 'logical reads: 123' AS metric FROM orders WHERE id = ?");
        assertThat(result.getRuntimeMetricsText()).isEqualTo("平均耗时: 1ms");
    }

    @Test
    void keepsLogicalAndPhysicalReadRowsInsideATextExecutionPlan() {
        String report = "SQL: SELECT id FROM orders WHERE tenant_id = ?\n"
                + "执行计划:\n"
                + "Logical Reads: 123\n"
                + "Physical Reads: 4\n"
                + "| ID | OPERATOR | NAME |\n"
                + "| 0 | TABLE FULL SCAN | orders |";

        ParsedReport result = parser.parse(report);

        assertThat(result.getExplainText())
                .contains("Logical Reads: 123", "Physical Reads: 4", "TABLE FULL SCAN");
        assertThat(result.getRuntimeMetricsText()).contains("逻辑读: 123", "物理读: 4");
    }

    @Test
    void treatsCountQueriesAtTheEndAsTableStatisticsNotExplain() {
        String report = "SQL: SELECT * FROM account WHERE status = ?\n"
                + "平均耗时: 800ms\n"
                + "执行计划:\n"
                + "SELECT COUNT(*) FROM account; 1,234,567";

        ParsedReport result = parser.parse(report);

        assertThat(result.getExplainText()).isEmpty();
        assertThat(result.getTableStatsText()).isEqualTo("account: 1234567 行");
        assertThat(result.getWarnings()).anyMatch(warning -> warning.contains("不包含计划算子"));
    }

    @Test
    void acceptsPlainSqlThatIsNotAReport() {
        ParsedReport result = parser.parse("SELECT id, name FROM customer WHERE tenant_id = ? LIMIT 20");

        assertThat(result.getExtractedSql()).isEqualTo(
                "SELECT id, name FROM customer WHERE tenant_id = ? LIMIT 20");
        assertThat(result.getInferredDialect()).isEqualTo("OceanBase MySQL");
        assertThat(result.getRuntimeMetricsText()).isEmpty();
        assertThat(result.getTableStatsText()).isEmpty();
        assertThat(result.getPriorAnalysisText()).isEmpty();
        assertThat(result.getExplainText()).isEmpty();
        assertThat(result.getWarnings()).anyMatch(warning -> warning.contains("按纯 SQL 处理"));
    }

    @Test
    void rejectsAReportWithoutSql() {
        String report = "SQL ID: B05FC9141039983E7E33ECD3A563E37D\n"
                + "执行次数: 21.88\nCPU占比: 41.7%\n平均耗时: 2008ms";

        assertThatThrownBy(() -> parser.parse(report))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未找到 SQL 字段");
    }

    @Test
    void parsesFollowUpEvidenceWithoutRequiringSqlAgain() {
        ParsedReport metrics = parser.parseSupplement("SQL ID: SYNTH-1\n"
                + "执行次数: 850\n平均耗时: 1860ms\n平均返回行数: 50\n"
                + "逻辑读: 2400000\n物理读: 18300\n"
                + "表统计:\norders: 12000000 行\ncustomers: 400000 行");

        assertThat(metrics.getExtractedSql()).isEmpty();
        assertThat(metrics.getRuntimeMetricsText())
                .contains("SQL ID: SYNTH-1", "平均耗时: 1860ms", "平均返回行数: 50 行")
                .contains("逻辑读: 2400000", "物理读: 18300");
        assertThat(metrics.getTableStatsText()).contains("orders: 12000000 行", "customers: 400000 行");

        ParsedReport planAndMetadata = parser.parseSupplement("OB Version: 4.3.5.1\n"
                + "EXPLAIN:\n| ID | OPERATOR | NAME | EST. ROWS |\n"
                + "| 0 | TABLE FULL SCAN | orders | 12000000 |\n"
                + "表结构:\nCREATE TABLE orders (id BIGINT PRIMARY KEY, tenant_id BIGINT);\n"
                + "现有索引:\nCREATE INDEX idx_orders_tenant ON orders(tenant_id);");

        assertThat(planAndMetadata.getExplainText()).contains("TABLE FULL SCAN", "12000000");
        assertThat(planAndMetadata.getSchemaText()).contains("CREATE TABLE orders");
        assertThat(planAndMetadata.getIndexText()).contains("idx_orders_tenant");
        assertThat(planAndMetadata.getObVersion()).isEqualTo("4.3.5.1");
    }

    @Test
    void rejectsInputLargerThan128KiB() {
        StringBuilder oversized = new StringBuilder("SELECT * FROM t WHERE note = '");
        while (oversized.length() <= ReportTextParser.MAX_INPUT_BYTES) {
            oversized.append('a');
        }
        oversized.append('\'');

        assertThatThrownBy(() -> parser.parse(oversized.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128 KiB");
    }
}
