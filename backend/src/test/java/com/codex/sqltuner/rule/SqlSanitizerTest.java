package com.codex.sqltuner.rule;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlSanitizerTest {
    @Test
    void sanitizeMasksLiteralsAndSensitiveValues() {
        SqlSanitizer sanitizer = new SqlSanitizer();

        SanitizedSql result = sanitizer.sanitize("select * from t where phone='13812345678' and id=123456789");

        assertThat(result.getSanitizedSql()).contains("'***'");
        assertThat(result.getSanitizedSql()).contains("***NUM***");
        assertThat(result.getSanitizedSql()).doesNotContain("13812345678");
        assertThat(result.getSqlHash()).isNotBlank();
    }
}
