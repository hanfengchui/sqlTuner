package com.codex.sqltuner.tuning.input;

import org.junit.jupiter.api.Test;

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
                        + "限流值: 0");
        assertThat(result.getTableStatsText()).isEqualTo(
                "GRP_CUSTOMER: 2294867 行\nGRP_CUSTOMER_EX: 2294758 行");
        assertThat(result.getPriorAnalysisText())
                .contains("历史根因（待核验，不作为事实证据）")
                .contains("执行计划异常")
                .contains("历史优化建议（待核验，不作为事实证据）")
                .contains("CREATE INDEX idx_grp_customer_ec_be");
        assertThat(result.getExplainText()).isEmpty();
        assertThat(result.getWarnings()).anyMatch(warning -> warning.contains("仅包含表行数查询"));
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
