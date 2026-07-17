package com.codex.sqltuner.tuning;

import com.codex.sqltuner.rule.RuleEngine;
import com.codex.sqltuner.rule.RuleFinding;
import com.codex.sqltuner.rule.SqlDialect;
import com.codex.sqltuner.rule.SqlSanitizer;
import com.codex.sqltuner.tuning.accuracy.SqlStatementParser;
import com.codex.sqltuner.tuning.accuracy.SqlStatementProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlAccuracyGoldenCaseTest {
    private final SqlStatementParser parser = new SqlStatementParser();
    private final SqlSanitizer sanitizer = new SqlSanitizer();
    private final RuleEngine ruleEngine = new RuleEngine();

    @Test
    void generatedGoldenCorpusCoversThirtyCasesPerDialect() {
        List<GoldenCase> cases = goldenCases();

        assertThat(cases).filteredOn("dialect", SqlDialect.OB_MYSQL).hasSize(30);
        assertThat(cases).filteredOn("dialect", SqlDialect.OB_ORACLE).hasSize(30);

        for (GoldenCase goldenCase : cases) {
            SqlStatementProfile profile = parser.parse(goldenCase.sql, goldenCase.dialect);
            assertThat(profile.isValid()).as(goldenCase.name).isTrue();
            assertThat(profile.getStatementType()).as(goldenCase.name).isIn("SELECT", "INSERT", "UPDATE", "DELETE");

            String sanitized = sanitizer.sanitize(goldenCase.sql).getSanitizedSql();
            assertThat(sanitized).as(goldenCase.name).doesNotContain("13812345678").doesNotContain("alice@example.com");

            List<RuleFinding> findings = ruleEngine.inspect(sanitized, goldenCase.indexText, goldenCase.explainText, goldenCase.dialect.getDisplayName());
            assertThat(findings).as(goldenCase.name).extracting("code").contains(goldenCase.expectedRule);
        }
    }

    @Test
    void parserRejectsDdlAndMultiStatement() {
        assertThat(parser.parse("create table t(id int)", SqlDialect.OB_MYSQL).isValid()).isFalse();
        assertThat(parser.parse("select * from t; delete from t", SqlDialect.OB_MYSQL).isValid()).isFalse();
    }

    private List<GoldenCase> goldenCases() {
        List<GoldenCase> cases = new ArrayList<GoldenCase>();
        addDialectCases(cases, SqlDialect.OB_MYSQL);
        addDialectCases(cases, SqlDialect.OB_ORACLE);
        return cases;
    }

    private void addDialectCases(List<GoldenCase> cases, SqlDialect dialect) {
        String pagination = dialect == SqlDialect.OB_MYSQL ? " limit 20" : " fetch first 20 rows only";
        String functionPredicate = dialect == SqlDialect.OB_MYSQL
                ? "select * from orders where date_format(created_at,'%Y-%m')='2026-07'"
                : "select * from orders where to_char(created_at,'yyyy-mm')='2026-07'";
        String explainFull = dialect == SqlDialect.OB_MYSQL ? "type: ALL; full scan; Extra: Using where" : "TABLE ACCESS FULL";
        cases.add(new GoldenCase(dialect.name() + "-select-star", dialect, "select * from orders where status='PAID'" + pagination, "", "", "SELECT_STAR"));
        cases.add(new GoldenCase(dialect.name() + "-leading-like", dialect, "select id from users where name like '%abc'" + pagination, "", "", "LEADING_LIKE"));
        cases.add(new GoldenCase(dialect.name() + "-function-column", dialect, functionPredicate + pagination, "", "", "FUNCTION_ON_COLUMN"));
        cases.add(new GoldenCase(dialect.name() + "-or-condition", dialect, "select id from orders where status='A' or status='B'" + pagination, "", "", "OR_CONDITION"));
        cases.add(new GoldenCase(dialect.name() + "-order-no-index", dialect, "select id from orders where status='A' order by created_at" + pagination, "", "", "ORDER_BY_NO_INDEX_CONTEXT"));
        cases.add(new GoldenCase(dialect.name() + "-full-scan", dialect, "select id from orders where buyer_id=100" + pagination, "", explainFull, "EXPLAIN_FULL_SCAN"));
        cases.add(new GoldenCase(dialect.name() + "-subquery", dialect, "select id from orders where user_id in (select id from users where phone='13812345678')" + pagination, "", "", "SUBQUERY"));
        cases.add(new GoldenCase(dialect.name() + "-temp-table", dialect, "select status,count(*) from orders group by status order by status" + pagination, "idx_status(status)", "Using temporary", "TEMP_TABLE"));
        cases.add(new GoldenCase(dialect.name() + "-back-table", dialect, "select amount from orders where status='A'" + pagination, "idx_status(status)", "table access by index rowid", "BACK_TABLE"));
        cases.add(new GoldenCase(dialect.name() + "-partition", dialect, "select id from orders where created_at>='2026-01-01'" + pagination, "idx_created(created_at)", "partitions: all", "PARTITION_PRUNING"));
        cases.add(new GoldenCase(dialect.name() + "-delete-no-where", dialect, "delete from audit_log", "", "", "DML_WITHOUT_WHERE"));
        cases.add(new GoldenCase(dialect.name() + "-update-no-where", dialect, "update orders set status='DONE'", "", "", "DML_WITHOUT_WHERE"));
        cases.add(new GoldenCase(dialect.name() + "-composite-prefix", dialect, "select id from orders where status='A' order by created_at" + pagination, "idx_status_created(status,created_at)", "", "COMPOSITE_INDEX_PREFIX"));
        cases.add(new GoldenCase(dialect.name() + "-no-pagination", dialect, "select id from orders where email='alice@example.com'", "", "", dialect == SqlDialect.OB_MYSQL ? "NO_LIMIT" : "NO_PAGINATION"));
        cases.add(new GoldenCase(dialect.name() + "-join-no-on", dialect, "select o.id from orders o join users u where o.user_id=u.id" + pagination, "", "", "JOIN_WITHOUT_ON"));

        cases.add(new GoldenCase(dialect.name() + "-select-star-2", dialect, "select * from users where id in (1,2,3)" + pagination, "", "", "SELECT_STAR"));
        cases.add(new GoldenCase(dialect.name() + "-leading-like-2", dialect, "select id from products where code like '%XYZ'" + pagination, "", "", "LEADING_LIKE"));
        cases.add(new GoldenCase(dialect.name() + "-function-column-2", dialect, functionPredicate.replace("orders", "users") + pagination, "", "", "FUNCTION_ON_COLUMN"));
        cases.add(new GoldenCase(dialect.name() + "-or-condition-2", dialect, "select id from tickets where priority='P1' or owner_id=10" + pagination, "", "", "OR_CONDITION"));
        cases.add(new GoldenCase(dialect.name() + "-order-no-index-2", dialect, "select id from tickets where status='OPEN' order by updated_at" + pagination, "", "", "ORDER_BY_NO_INDEX_CONTEXT"));
        cases.add(new GoldenCase(dialect.name() + "-full-scan-2", dialect, "select id from users where phone='13812345678'" + pagination, "", explainFull, "EXPLAIN_FULL_SCAN"));
        cases.add(new GoldenCase(dialect.name() + "-subquery-2", dialect, "select id from users where exists (select 1 from orders where orders.user_id=users.id)" + pagination, "", "", "SUBQUERY"));
        cases.add(new GoldenCase(dialect.name() + "-temp-table-2", dialect, "select category,count(*) from products group by category order by category" + pagination, "idx_category(category)", "Using temporary", "TEMP_TABLE"));
        cases.add(new GoldenCase(dialect.name() + "-back-table-2", dialect, "select title from tickets where status='OPEN'" + pagination, "idx_status(status)", "回表", "BACK_TABLE"));
        cases.add(new GoldenCase(dialect.name() + "-partition-2", dialect, "select id from metrics where metric_day>='2026-01-01'" + pagination, "idx_day(metric_day)", "partition: all", "PARTITION_PRUNING"));
        cases.add(new GoldenCase(dialect.name() + "-delete-no-where-2", dialect, "delete from sessions", "", "", "DML_WITHOUT_WHERE"));
        cases.add(new GoldenCase(dialect.name() + "-update-no-where-2", dialect, "update users set flag=1", "", "", "DML_WITHOUT_WHERE"));
        cases.add(new GoldenCase(dialect.name() + "-composite-prefix-2", dialect, "select id from tickets where status='OPEN' order by updated_at" + pagination, "idx_status_updated(status,updated_at)", "", "COMPOSITE_INDEX_PREFIX"));
        cases.add(new GoldenCase(dialect.name() + "-no-pagination-2", dialect, "select id from users where email='alice@example.com'", "", "", dialect == SqlDialect.OB_MYSQL ? "NO_LIMIT" : "NO_PAGINATION"));
        cases.add(new GoldenCase(dialect.name() + "-join-no-on-2", dialect, "select u.id from users u join orgs g where u.org_id=g.id" + pagination, "", "", "JOIN_WITHOUT_ON"));
    }

    private static class GoldenCase {
        private final String name;
        private final SqlDialect dialect;
        private final String sql;
        private final String indexText;
        private final String explainText;
        private final String expectedRule;

        private GoldenCase(String name, SqlDialect dialect, String sql, String indexText, String explainText, String expectedRule) {
            this.name = name;
            this.dialect = dialect;
            this.sql = sql;
            this.indexText = indexText;
            this.explainText = explainText;
            this.expectedRule = expectedRule;
        }
    }
}
