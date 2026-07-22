package com.codex.sqltuner.rule;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlSanitizerTest {
    @Test
    void sanitizeMasksLiteralsAndSensitiveValues() {
        SqlSanitizer sanitizer = new SqlSanitizer();

        SanitizedSql result = sanitizer.sanitize("select * from t where phone='13812345678' and id=123456789");

        assertThat(result.getSanitizedSql()).contains("'__STR_NUMERIC__'");
        assertThat(result.getSanitizedSql()).contains("__NUM_INT__");
        assertThat(result.getSanitizedSql()).doesNotContain("13812345678");
        assertThat(result.getSqlHash()).isNotBlank();
    }

    @Test
    void astSanitizerPreservesLikeInAndDateShapes() {
        SqlSanitizer sanitizer = new SqlSanitizer();

        SanitizedSql result = sanitizer.sanitize(
                "select id from orders where name like '%abc' and created_at >= '2026-07-01' and status in ('A','B','C')");

        assertThat(result.getSanitizedSql()).contains("LIKE_LEADING_WILDCARD");
        assertThat(result.getSanitizedSql()).contains("__DATE_LITERAL__");
        assertThat(result.getSanitizedSql()).contains("__IN_LIST_3__");
        assertThat(result.getSanitizedSql()).doesNotContain("%abc", "2026-07-01", "'A'", "'B'", "'C'");
    }

    @Test
    void fallsBackWhenOracleUpdateAstTraversalRecurses() {
        SqlSanitizer sanitizer = new SqlSanitizer();
        String sql = "UPDATE \"JYZX1\".\"OPENAPI_FQLS_SALE_SYNC_MID\" \"A\" "
                + "SET \"A\".\"F_SYNC_STATUS\" = ("
                + "SELECT NVL(MAX(\"SC\".\"F_SWITCH\"), ?) "
                + "FROM \"JYZX1\".\"TB_ZDGS_SYNC_CONFIG\" \"SC\" "
                + "WHERE \"A\".\"F_PROV_NUM\" = \"SC\".\"F_PROV_NUM\" "
                + "AND \"SC\".\"F_BIZ_TYPE\" = ?) "
                + "WHERE \"A\".\"F_STATUS\" = ? AND \"A\".\"F_IS_ZDGS\" = ?;";

        SanitizedSql result = sanitizer.sanitize(sql, SqlDialect.OB_ORACLE);

        assertThat(result.getSanitizedSql()).contains("UPDATE", "F_SYNC_STATUS", "F_BIZ_TYPE");
        assertThat(result.getSqlHash()).isNotBlank();
    }
}
