package com.codex.sqltuner.rule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class RuleEngine {
    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);
    private static final Pattern SELECT_STAR = Pattern.compile("(?is)select\\s+\\*\\s+from");
    private static final Pattern LEADING_LIKE = Pattern.compile("(?is)like\\s+'%[^']*'");
    private static final Pattern COMMON_FUNCTION_ON_COLUMN = Pattern.compile("(?is)(where|and|or)\\s+(substr|substring|lower|upper|trim)\\s*\\(");
    private static final Pattern MYSQL_FUNCTION_ON_COLUMN = Pattern.compile("(?is)(where|and|or)\\s+(date_format|year|month)\\s*\\(");
    private static final Pattern ORACLE_FUNCTION_ON_COLUMN = Pattern.compile("(?is)(where|and|or)\\s+(to_char|trunc|nvl|decode)\\s*\\(");
    private static final Pattern OR_CONDITION = Pattern.compile("(?is)\\sor\\s+");
    private static final Pattern ORDER_BY = Pattern.compile("(?is)order\\s+by\\s+");
    private static final Pattern LIMIT = Pattern.compile("(?is)\\slimit\\s+\\d+");
    private static final Pattern ORACLE_PAGINATION = Pattern.compile("(?is)(rownum\\s*(<=|<|=)|fetch\\s+first\\s+\\d+\\s+rows|offset\\s+\\d+\\s+rows)");

    public List<RuleFinding> inspect(String sanitizedSql, String indexText, String explainText) {
        return inspect(sanitizedSql, indexText, explainText, SqlDialect.OB_MYSQL.getDisplayName());
    }

    public List<RuleFinding> inspect(String sanitizedSql, String indexText, String explainText, String dbDialect) {
        SqlDialect dialect = SqlDialect.from(dbDialect);
        log.info("inspectRules param 入参: sqlLength: {}, hasIndexText: {}, hasExplainText: {}",
                sanitizedSql == null ? 0 : sanitizedSql.length(), hasText(indexText), hasText(explainText));
        List<RuleFinding> findings = new ArrayList<RuleFinding>();
        String sql = sanitizedSql == null ? "" : sanitizedSql;
        String lower = sql.toLowerCase(Locale.ROOT);

        if (SELECT_STAR.matcher(sql).find()) {
            findings.add(new RuleFinding("SELECT_STAR", RuleSeverity.WARN, "存在 SELECT *",
                    "SQL 使用 SELECT *", "只返回业务需要字段，减少回表、网络传输和对象构造成本。"));
        }
        if (LEADING_LIKE.matcher(sql).find()) {
            findings.add(new RuleFinding("LEADING_LIKE", RuleSeverity.HIGH, "前置通配 LIKE 可能无法利用 BTree 索引",
                    "检测到 LIKE '%...'", "评估改为右前缀匹配、倒排索引、全文索引或增加可检索冗余字段。"));
        }
        if (COMMON_FUNCTION_ON_COLUMN.matcher(sql).find()
                || (dialect == SqlDialect.OB_MYSQL && MYSQL_FUNCTION_ON_COLUMN.matcher(sql).find())
                || (dialect == SqlDialect.OB_ORACLE && ORACLE_FUNCTION_ON_COLUMN.matcher(sql).find())) {
            findings.add(new RuleFinding("FUNCTION_ON_COLUMN", RuleSeverity.HIGH, "索引列可能被函数包裹",
                    "WHERE 条件中出现 " + dialect.getDisplayName() + " 函数调用", functionSuggestion(dialect)));
        }
        if (OR_CONDITION.matcher(sql).find()) {
            findings.add(new RuleFinding("OR_CONDITION", RuleSeverity.WARN, "OR 条件可能扩大扫描范围",
                    "SQL 存在 OR 条件", "检查每个 OR 分支是否都有合适索引，必要时改写为 UNION ALL 后分别命中索引。"));
        }
        if (ORDER_BY.matcher(sql).find() && !hasText(indexText)) {
            findings.add(new RuleFinding("ORDER_BY_NO_INDEX_CONTEXT", RuleSeverity.INFO, "存在排序但未提供索引信息",
                    "SQL 存在 ORDER BY，索引上下文为空", "补充排序字段相关索引，确认是否出现 filesort 或大范围排序。"));
        }
        if (dialect == SqlDialect.OB_MYSQL && !LIMIT.matcher(lower).find() && lower.contains("select")) {
            findings.add(new RuleFinding("NO_LIMIT", RuleSeverity.INFO, "查询未显式限制返回行数",
                    "未检测到 LIMIT", "面向列表或排查查询时建议增加分页或明确业务上限。"));
        }
        if (dialect == SqlDialect.OB_ORACLE && lower.contains("select") && !ORACLE_PAGINATION.matcher(lower).find()) {
            findings.add(new RuleFinding("NO_PAGINATION", RuleSeverity.INFO, "查询未显式限制返回行数",
                    "未检测到 ROWNUM / FETCH FIRST / OFFSET FETCH", "面向列表或排查查询时建议增加 Oracle 兼容分页或明确业务上限。"));
        }
        if (hasText(explainText) && explainText.toLowerCase(Locale.ROOT).contains("full")) {
            findings.add(new RuleFinding("EXPLAIN_FULL_SCAN", RuleSeverity.HIGH, "执行计划提示可能存在全表扫描",
                    "EXPLAIN 中出现 full", "结合 where 条件和表行数补充组合索引，优先验证过滤性最高字段。"));
        }

        log.info("inspectRules result 结果: dialect: {}, findingCount: {}", dialect.getDisplayName(), findings.size());
        return findings;
    }

    private String functionSuggestion(SqlDialect dialect) {
        if (dialect == SqlDialect.OB_ORACLE) {
            return "优先改写为范围条件或函数索引；涉及 TRUNC/TO_CHAR 日期过滤时，先验证执行计划和写入成本。";
        }
        return "将函数计算移到常量侧，或增加生成列/函数索引并验证执行计划。";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
