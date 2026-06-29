package com.codex.sqltuner.rule;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {
    @Test
    void inspectFindsCommonSqlRisks() {
        RuleEngine engine = new RuleEngine();

        List<RuleFinding> findings = engine.inspect(
                "select * from orders where date_format(create_time,'%Y-%m')='***' or name like '%abc' order by create_time",
                "",
                "type: ALL full scan",
                "OceanBase MySQL"
        );

        assertThat(findings).extracting("code").contains("SELECT_STAR", "LEADING_LIKE", "FUNCTION_ON_COLUMN", "OR_CONDITION", "EXPLAIN_FULL_SCAN");
    }

    @Test
    void inspectFindsOracleCompatibleRisks() {
        RuleEngine engine = new RuleEngine();

        List<RuleFinding> findings = engine.inspect(
                "select * from orders where to_char(created_at, 'yyyy-mm-dd') = '***' order by created_at",
                "",
                "TABLE ACCESS FULL",
                "OceanBase Oracle"
        );

        assertThat(findings).extracting("code").contains("SELECT_STAR", "FUNCTION_ON_COLUMN", "NO_PAGINATION", "EXPLAIN_FULL_SCAN");
        assertThat(findings).extracting("code").doesNotContain("NO_LIMIT");
    }

    @Test
    void inspectAcceptsOraclePagination() {
        RuleEngine engine = new RuleEngine();

        List<RuleFinding> findings = engine.inspect(
                "select id from orders where status = '***' fetch first 20 rows only",
                "",
                "",
                "OB_ORACLE"
        );

        assertThat(findings).extracting("code").doesNotContain("NO_PAGINATION");
    }
}
