package com.codex.sqltuner.tuning.input;

import com.codex.sqltuner.rule.SqlDialect;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReportSqlExtractor {
    static final Pattern SQL_LABEL = Pattern.compile("(?i)(?<![A-Za-z0-9_])SQL(?!\\s*ID\\b)\\s*[:：]\\s*");
    private static final Pattern SQL_START = Pattern.compile("(?is)^\\s*(?:SELECT|WITH|INSERT|UPDATE|DELETE)\\b");
    private static final Pattern ORACLE_DIALECT = Pattern.compile("(?is)(?:\\bROWNUM\\b|\\bFETCH\\s+FIRST\\b|\"[^\"\\r\\n]+\")");

    boolean looksLikeSql(String text) {
        return SQL_START.matcher(text).find();
    }

    String inferDialect(String sql) {
        return ORACLE_DIALECT.matcher(sql).find()
                ? SqlDialect.OB_ORACLE.getDisplayName()
                : SqlDialect.OB_MYSQL.getDisplayName();
    }

    SqlExtraction extractLabeledSql(String text, List<Pattern> endLabels) {
        Matcher sqlLabel = SQL_LABEL.matcher(text);
        if (!sqlLabel.find()) {
            throw new IllegalArgumentException("报告中未找到 SQL 字段");
        }
        int sqlEnd = ReportParsingSupport.firstLabelStart(text, sqlLabel.end(), endLabels);
        String sql = text.substring(sqlLabel.end(), sqlEnd).trim();
        if (!looksLikeSql(sql)) {
            throw new IllegalArgumentException("报告中的 SQL 字段为空或不是可识别的 SQL");
        }
        return new SqlExtraction(sql, sqlLabel.start(), sqlEnd);
    }

    static final class SqlExtraction {
        private final String sql;
        private final int labelStart;
        private final int end;

        private SqlExtraction(String sql, int labelStart, int end) {
            this.sql = sql;
            this.labelStart = labelStart;
            this.end = end;
        }

        String getSql() {
            return sql;
        }

        String reportMetricText(String text) {
            return text.substring(0, labelStart) + "\n" + text.substring(end);
        }
    }
}
