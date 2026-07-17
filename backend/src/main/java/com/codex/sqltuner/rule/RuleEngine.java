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
    private static final Pattern LEADING_LIKE = Pattern.compile("(?is)like\\s+('%[^']*'|'LIKE_LEADING_WILDCARD'|'LIKE_BOTH_WILDCARD')");
    private static final Pattern COMMON_FUNCTION_ON_COLUMN = Pattern.compile("(?is)(where|and|or)\\s+(substr|substring|lower|upper|trim)\\s*\\(");
    private static final Pattern MYSQL_FUNCTION_ON_COLUMN = Pattern.compile("(?is)(where|and|or)\\s+(date_format|year|month)\\s*\\(");
    private static final Pattern ORACLE_FUNCTION_ON_COLUMN = Pattern.compile("(?is)(where|and|or)\\s+(to_char|trunc|nvl|decode)\\s*\\(");
    private static final Pattern OR_CONDITION = Pattern.compile("(?is)\\sor\\s+");
    private static final Pattern ORDER_BY = Pattern.compile("(?is)order\\s+by\\s+");
    private static final Pattern WHERE = Pattern.compile("(?is)\\bwhere\\b");
    private static final String INTEGER_SHAPE = "(?:\\d+|__NUM_INT__)";
    private static final Pattern LIMIT = Pattern.compile("(?is)\\slimit\\s+" + INTEGER_SHAPE);
    private static final Pattern ORACLE_PAGINATION = Pattern.compile("(?is)(rownum\\s*(<=|<|=)|fetch\\s+first\\s+" + INTEGER_SHAPE + "\\s+rows|offset\\s+" + INTEGER_SHAPE + "\\s+rows)");
    private static final Pattern IMPLICIT_STRING_NUMBER = Pattern.compile("(?is)(where|and|or)\\s+[\\w.]+\\s*=\\s*'__STR_NUMERIC__'|\\b(char|varchar|text)\\b[^\\n,]*=\\s*__NUM_");
    private static final Pattern JOIN_WITHOUT_ON = Pattern.compile("(?is)\\bjoin\\s+[\\w.`\"]+(\\s+\\w+)?\\s+(where|group\\s+by|order\\s+by|limit|fetch\\s+first|$)");
    private static final Pattern SUBQUERY = Pattern.compile("(?is)\\b(in|exists)\\s*\\(\\s*select\\b");
    private static final Pattern TEMP_TABLE_EXPLAIN = Pattern.compile("(?is)(using temporary|temp table|temporary)");
    private static final Pattern BACK_TABLE_EXPLAIN = Pattern.compile("(?is)(table access by index rowid|回表|back to table)");
    private static final Pattern PARTITION_RISK = Pattern.compile("(?is)\\bpartition\\b|\\bpartitions?:\\s*(all|null)");
    private static final Pattern DML_WITHOUT_WHERE = Pattern.compile("(?is)^\\s*(update|delete)\\b(?!.*\\bwhere\\b)");
    private static final Pattern LARGE_OFFSET = Pattern.compile("(?is)\\blimit\\s+__NUM_INT__\\s*,|\\boffset\\s+__NUM_INT__\\s+rows");

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
        if (IMPLICIT_STRING_NUMBER.matcher(sql).find()) {
            findings.add(new RuleFinding("IMPLICIT_TYPE_CONVERSION", RuleSeverity.HIGH, "可能存在隐式类型转换",
                    "条件两侧出现字符串/数值形态不一致", "确认列类型与绑定变量类型一致，避免索引列被隐式转换后失去索引能力。"));
        }
        if (JOIN_WITHOUT_ON.matcher(sql).find()) {
            findings.add(new RuleFinding("JOIN_WITHOUT_ON", RuleSeverity.HIGH, "JOIN 可能缺少 ON 条件",
                    "检测到 JOIN 后未出现明确 ON 条件", "补充连接条件并确认驱动表、连接列索引和结果集基数。"));
        }
        if (SUBQUERY.matcher(sql).find()) {
            findings.add(new RuleFinding("SUBQUERY", RuleSeverity.WARN, "子查询可能影响优化器选择",
                    "检测到 IN/EXISTS 子查询", "结合执行计划判断是否可改写为 JOIN、半连接或预聚合，避免重复扫描。"));
        }
        if (ORDER_BY.matcher(sql).find() && !hasText(indexText)) {
            findings.add(new RuleFinding("ORDER_BY_NO_INDEX_CONTEXT", RuleSeverity.INFO, "存在排序但未提供索引信息",
                    "SQL 存在 ORDER BY，索引上下文为空", "补充排序字段相关索引，确认是否出现 filesort 或大范围排序。"));
        }
        if ((ORDER_BY.matcher(sql).find() || lower.contains("group by")) && hasText(explainText)
                && TEMP_TABLE_EXPLAIN.matcher(explainText).find()) {
            findings.add(new RuleFinding("TEMP_TABLE", RuleSeverity.HIGH, "排序/聚合可能产生临时表",
                    "EXPLAIN 中出现 temporary", "检查 GROUP/ORDER 字段顺序与索引前缀，必要时拆分或预聚合。"));
        }
        if (hasText(indexText) && WHERE.matcher(sql).find() && ORDER_BY.matcher(sql).find()) {
            findings.add(new RuleFinding("COMPOSITE_INDEX_PREFIX", RuleSeverity.INFO, "可检查复合索引前缀匹配",
                    "SQL 同时存在过滤和排序，且已提供索引上下文", "验证等值列、范围列、排序列是否符合组合索引最左前缀和范围截断规律。"));
        }
        if (LARGE_OFFSET.matcher(sql).find()) {
            findings.add(new RuleFinding("OFFSET_PAGINATION", RuleSeverity.WARN, "深分页可能扫描并丢弃大量行",
                    "检测到 OFFSET/LIMIT offset 形态", "评估基于上次游标的 seek pagination，并验证排序稳定性。"));
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
        if (hasText(explainText) && BACK_TABLE_EXPLAIN.matcher(explainText).find()) {
            findings.add(new RuleFinding("BACK_TABLE", RuleSeverity.WARN, "执行计划提示可能存在回表成本",
                    "EXPLAIN 中出现回表相关描述", "评估覆盖索引收益，但必须结合写入成本和选择性验证。"));
        }
        if (hasText(explainText) && PARTITION_RISK.matcher(explainText).find()) {
            findings.add(new RuleFinding("PARTITION_PRUNING", RuleSeverity.WARN, "可能未命中分区裁剪",
                    "执行计划中出现分区扫描风险信号", "补充分区键条件，确认函数或隐式转换是否阻断分区裁剪。"));
        }
        if (DML_WITHOUT_WHERE.matcher(sql).find()) {
            findings.add(new RuleFinding("DML_WITHOUT_WHERE", RuleSeverity.HIGH, "DML 缺少 WHERE 条件",
                    "UPDATE/DELETE 未检测到 WHERE", "确认是否为全表变更；生产执行前必须增加业务条件或人工审批。"));
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
