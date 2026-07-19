package com.codex.sqltuner.tuning.accuracy;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLLimit;
import com.alibaba.druid.sql.ast.SQLOrderBy;
import com.alibaba.druid.sql.ast.SQLOrderingSpecification;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLBetweenExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.ast.expr.SQLInListExpr;
import com.alibaba.druid.sql.ast.expr.SQLValuableExpr;
import com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectGroupByClause;
import com.alibaba.druid.sql.ast.statement.SQLSelectOrderByItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.alibaba.druid.sql.dialect.oracle.visitor.OracleASTVisitorAdapter;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlSchemaStatVisitor;
import com.alibaba.druid.sql.dialect.oracle.visitor.OracleSchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;
import com.codex.sqltuner.rule.SqlDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SqlStatementParser {
    private static final Logger log = LoggerFactory.getLogger(SqlStatementParser.class);
    private static final Pattern DDL = Pattern.compile("(?is)^\\s*(create|alter|drop|truncate|rename|grant|revoke)\\b");

    public SqlStatementProfile parse(String sql, SqlDialect dialect) {
        SqlStatementProfile profile = new SqlStatementProfile();
        profile.setDialect(dialect);
        String value = sql == null ? "" : sql.trim();
        if (value.isEmpty()) {
            profile.setReason("SQL 不能为空");
            return profile;
        }
        profile.setMultiStatement(hasMultipleStatements(value));
        profile.setDdl(DDL.matcher(value).find());
        if (profile.isMultiStatement()) {
            profile.setReason("仅支持单条 SQL，拒绝多语句输入");
            return profile;
        }
        if (profile.isDdl()) {
            profile.setReason("仅支持 SELECT/INSERT/UPDATE/DELETE，拒绝 DDL 或权限语句");
            return profile;
        }

        String dbType = dialect == SqlDialect.OB_ORACLE ? "oceanbase_oracle" : "oceanbase";
        List<SQLStatement> statements;
        try {
            statements = SQLUtils.parseStatements(value, dbType);
        } catch (RuntimeException e) {
            log.warn("parseSql result 结果: Druid 解析失败, exceptionType: {}", e.getClass().getName());
            profile.setReason("SQL 无法按 " + dialect.getDisplayName() + " 解析");
            return profile;
        }
        if (statements.size() != 1) {
            profile.setReason("仅支持单条 SQL，拒绝多语句输入");
            return profile;
        }
        SQLStatement statement = statements.get(0);
        String type = statementType(statement);
        profile.setStatementType(type);
        if (!("SELECT".equals(type) || "INSERT".equals(type) || "UPDATE".equals(type) || "DELETE".equals(type))) {
            profile.setReason("仅支持单条 SELECT、INSERT、UPDATE、DELETE");
            return profile;
        }

        profile.setTables(extractTables(statement, dialect));
        profile.setIndexRelevantColumns(extractIndexRelevantColumns(statement, dialect));
        fillSemanticProfile(profile, statement);
        String lower = value.toLowerCase(Locale.ROOT);
        profile.setHasWhere(containsWord(lower, "where") && !profile.getWherePredicates().isEmpty());
        profile.setHasJoin(!profile.getJoinSignatures().isEmpty());
        profile.setHasGroupBy(!profile.getGroupByItems().isEmpty());
        profile.setHasOrderBy(!profile.getOrderByItems().isEmpty());
        profile.setHasPagination(!profile.getPaginationSignatures().isEmpty());
        profile.setValid(true);
        return profile;
    }

    private void fillSemanticProfile(SqlStatementProfile profile, SQLStatement statement) {
        List<SQLSelectQueryBlock> queryBlocks = collectQueryBlocks(statement, profile.getDialect());
        for (SQLSelectQueryBlock queryBlock : queryBlocks) {
            collectWherePredicates(queryBlock.getWhere(), profile.getWherePredicates(), profile.getPaginationSignatures());
            collectJoins(queryBlock.getFrom(), profile.getJoinSignatures());
            collectGroupBy(queryBlock.getGroupBy(), profile.getGroupByItems());
            collectOrderBy(queryBlock.getOrderBy(), profile.getOrderByItems());
            collectLimit(queryBlock.getLimit(), profile.getPaginationSignatures());
        }
        if (statement instanceof SQLSelectStatement) {
            SQLSelect select = ((SQLSelectStatement) statement).getSelect();
            collectOrderBy(select == null ? null : select.getOrderBy(), profile.getOrderByItems());
            collectLimit(select == null ? null : select.getLimit(), profile.getPaginationSignatures());
            if (select != null && select.getRowCount() != null) {
                profile.getPaginationSignatures().add("TOP_N");
            }
        } else if (statement instanceof SQLUpdateStatement) {
            SQLUpdateStatement update = (SQLUpdateStatement) statement;
            collectWherePredicates(update.getWhere(), profile.getWherePredicates(), profile.getPaginationSignatures());
            collectJoins(update.getTableSource(), profile.getJoinSignatures());
            collectJoins(update.getFrom(), profile.getJoinSignatures());
            collectOrderBy(update.getOrderBy(), profile.getOrderByItems());
            collectLimit(update.getLimit(), profile.getPaginationSignatures());
        } else if (statement instanceof SQLDeleteStatement) {
            SQLDeleteStatement delete = (SQLDeleteStatement) statement;
            collectWherePredicates(delete.getWhere(), profile.getWherePredicates(), profile.getPaginationSignatures());
            collectJoins(delete.getTableSource(), profile.getJoinSignatures());
            collectJoins(delete.getFrom(), profile.getJoinSignatures());
        }
        sortUnique(profile.getWherePredicates());
        sortUnique(profile.getJoinSignatures());
        sortUnique(profile.getGroupByItems());
        sortUnique(profile.getOrderByItems());
        sortUnique(profile.getPaginationSignatures());
    }

    private List<SQLSelectQueryBlock> collectQueryBlocks(SQLStatement statement, SqlDialect dialect) {
        final List<SQLSelectQueryBlock> queryBlocks = new ArrayList<SQLSelectQueryBlock>();
        if (dialect == SqlDialect.OB_ORACLE) {
            statement.accept(new OracleASTVisitorAdapter() {
                @Override
                public boolean visit(SQLSelectQueryBlock x) {
                    queryBlocks.add(x);
                    return true;
                }
            });
        } else {
            statement.accept(new MySqlASTVisitorAdapter() {
                @Override
                public boolean visit(SQLSelectQueryBlock x) {
                    queryBlocks.add(x);
                    return true;
                }
            });
        }
        return queryBlocks;
    }

    private void collectWherePredicates(SQLExpr expr, List<String> predicates, List<String> pagination) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SQLBinaryOpExpr && ((SQLBinaryOpExpr) expr).getOperator() == SQLBinaryOperator.BooleanAnd) {
            SQLBinaryOpExpr binary = (SQLBinaryOpExpr) expr;
            collectWherePredicates(binary.getLeft(), predicates, pagination);
            collectWherePredicates(binary.getRight(), predicates, pagination);
            return;
        }
        String paginationSignature = paginationFromRownum(expr);
        if (paginationSignature != null) {
            pagination.add(paginationSignature);
            return;
        }
        String atom = conditionSignature(expr);
        if (hasText(atom)) {
            predicates.add(atom);
        }
    }

    private void collectJoins(SQLTableSource tableSource, List<String> joins) {
        if (tableSource == null) {
            return;
        }
        if (tableSource instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) tableSource;
            List<String> onConditions = new ArrayList<String>();
            collectJoinConditions(join.getCondition(), onConditions);
            sortUnique(onConditions);
            joins.add("join:" + joinType(join) + ":on:" + onConditions);
            collectJoins(join.getLeft(), joins);
            collectJoins(join.getRight(), joins);
        }
    }

    private void collectJoinConditions(SQLExpr expr, List<String> conditions) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SQLBinaryOpExpr && ((SQLBinaryOpExpr) expr).getOperator() == SQLBinaryOperator.BooleanAnd) {
            SQLBinaryOpExpr binary = (SQLBinaryOpExpr) expr;
            collectJoinConditions(binary.getLeft(), conditions);
            collectJoinConditions(binary.getRight(), conditions);
            return;
        }
        conditions.add(conditionSignature(expr));
    }

    private String joinType(SQLJoinTableSource join) {
        return join.getJoinType() == null ? "JOIN" : join.getJoinType().name();
    }

    private void collectGroupBy(SQLSelectGroupByClause groupBy, List<String> items) {
        if (groupBy == null) {
            return;
        }
        for (SQLExpr item : groupBy.getItems()) {
            items.add(exprKey(item));
        }
    }

    private void collectOrderBy(SQLOrderBy orderBy, List<String> items) {
        if (orderBy == null) {
            return;
        }
        for (SQLSelectOrderByItem item : orderBy.getItems()) {
            SQLOrderingSpecification type = item.getType();
            items.add(exprKey(item.getExpr()) + ":" + (type == null ? "ASC" : type.name()));
        }
    }

    private void collectLimit(SQLLimit limit, List<String> pagination) {
        if (limit == null) {
            return;
        }
        pagination.add(limit.getOffset() == null ? "LIMIT" : "LIMIT_OFFSET");
    }

    private String conditionSignature(SQLExpr expr) {
        if (expr instanceof SQLBetweenExpr) {
            SQLBetweenExpr between = (SQLBetweenExpr) expr;
            return exprKey(between.getTestExpr()) + "|" + (between.isNot() ? "NOT_BETWEEN" : "BETWEEN");
        }
        if (expr instanceof SQLInListExpr) {
            SQLInListExpr in = (SQLInListExpr) expr;
            return exprKey(in.getExpr()) + "|" + (in.isNot() ? "NOT_IN" : "IN") + "#" + in.getTargetList().size();
        }
        if (expr instanceof SQLBinaryOpExpr) {
            SQLBinaryOpExpr binary = (SQLBinaryOpExpr) expr;
            SQLBinaryOperator operator = binary.getOperator();
            if (operator == SQLBinaryOperator.BooleanOr) {
                List<String> parts = new ArrayList<String>();
                parts.add(conditionSignature(binary.getLeft()));
                parts.add(conditionSignature(binary.getRight()));
                Collections.sort(parts);
                return "or:" + parts;
            }
            if (isLike(operator)) {
                return exprKey(binary.getLeft()) + "|" + operator.name() + "|" + likeShape(binary.getRight());
            }
            if (operator != null && operator.isRelational()) {
                return relationalSignature(binary.getLeft(), operator, binary.getRight());
            }
        }
        return "expr:" + exprKey(expr);
    }

    private String relationalSignature(SQLExpr left, SQLBinaryOperator operator, SQLExpr right) {
        if (isLiteral(left) && !isLiteral(right)) {
            return exprKey(right) + "|" + reverse(operator).name();
        }
        if (!isLiteral(left) && isLiteral(right)) {
            return exprKey(left) + "|" + operator.name();
        }
        if (operator == SQLBinaryOperator.Equality || operator == SQLBinaryOperator.EqEq) {
            List<String> sides = new ArrayList<String>();
            sides.add(exprKey(left));
            sides.add(exprKey(right));
            Collections.sort(sides);
            return sides.get(0) + "|" + operator.name() + "|" + sides.get(1);
        }
        return exprKey(left) + "|" + operator.name() + "|" + exprKey(right);
    }

    private SQLBinaryOperator reverse(SQLBinaryOperator operator) {
        if (operator == SQLBinaryOperator.GreaterThan) {
            return SQLBinaryOperator.LessThan;
        }
        if (operator == SQLBinaryOperator.GreaterThanOrEqual) {
            return SQLBinaryOperator.LessThanOrEqual;
        }
        if (operator == SQLBinaryOperator.LessThan) {
            return SQLBinaryOperator.GreaterThan;
        }
        if (operator == SQLBinaryOperator.LessThanOrEqual) {
            return SQLBinaryOperator.GreaterThanOrEqual;
        }
        return operator;
    }

    private boolean isLike(SQLBinaryOperator operator) {
        return operator == SQLBinaryOperator.Like
                || operator == SQLBinaryOperator.NotLike
                || operator == SQLBinaryOperator.ILike
                || operator == SQLBinaryOperator.NotILike;
    }

    private String paginationFromRownum(SQLExpr expr) {
        if (!(expr instanceof SQLBinaryOpExpr)) {
            return null;
        }
        SQLBinaryOpExpr binary = (SQLBinaryOpExpr) expr;
        if (containsRownum(binary.getLeft()) || containsRownum(binary.getRight())) {
            return "TOP_N";
        }
        return null;
    }

    private boolean containsRownum(SQLExpr expr) {
        return expr != null && exprKey(expr).equals("rownum");
    }

    private String exprKey(SQLExpr expr) {
        if (expr == null) {
            return "";
        }
        if (isLiteral(expr)) {
            return "?";
        }
        return normalizeText(expr.toString());
    }

    private boolean isLiteral(SQLExpr expr) {
        return expr instanceof SQLValuableExpr || expr instanceof SQLVariantRefExpr;
    }

    private String likeShape(SQLExpr expr) {
        if (expr instanceof SQLCharExpr) {
            Object value = ((SQLCharExpr) expr).getValue();
            String text = value == null ? "" : String.valueOf(value);
            boolean leading = text.startsWith("%");
            boolean trailing = text.endsWith("%");
            if (leading && trailing) {
                return "LIKE_BOTH_SIDES";
            }
            if (leading) {
                return "LIKE_LEADING";
            }
            if (trailing) {
                return "LIKE_TRAILING";
            }
            return "LIKE_EXACT";
        }
        return "LIKE_EXPR";
    }

    private void sortUnique(List<String> values) {
        Set<String> unique = new LinkedHashSet<String>(values);
        values.clear();
        values.addAll(unique);
        Collections.sort(values);
    }

    private String statementType(SQLStatement statement) {
        if (statement instanceof SQLSelectStatement) {
            return "SELECT";
        }
        if (statement instanceof SQLInsertStatement) {
            return "INSERT";
        }
        if (statement instanceof SQLUpdateStatement) {
            return "UPDATE";
        }
        if (statement instanceof SQLDeleteStatement) {
            return "DELETE";
        }
        return "";
    }

    private boolean hasMultipleStatements(String sql) {
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                if (inString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    inString = !inString;
                }
            } else if (!inString && c == ';' && hasText(sql.substring(i + 1))) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractTables(SQLStatement statement, SqlDialect dialect) {
        Set<String> tables = new LinkedHashSet<String>();
        SchemaStatVisitor visitor = dialect == SqlDialect.OB_ORACLE
                ? new OracleSchemaStatVisitor()
                : new MySqlSchemaStatVisitor();
        statement.accept(visitor);
        for (TableStat.Name name : visitor.getTables().keySet()) {
            tables.add(cleanIdentifier(name.getName()));
        }
        return new ArrayList<String>(tables);
    }

    private List<String> extractIndexRelevantColumns(SQLStatement statement, SqlDialect dialect) {
        Set<String> columns = new LinkedHashSet<String>();
        SchemaStatVisitor visitor = dialect == SqlDialect.OB_ORACLE
                ? new OracleSchemaStatVisitor()
                : new MySqlSchemaStatVisitor();
        statement.accept(visitor);
        Set<TableStat.Column> orderByColumns = new LinkedHashSet<TableStat.Column>(visitor.getOrderByColumns());
        for (TableStat.Column column : visitor.getColumns()) {
            if (!(column.isWhere() || column.isJoin() || column.isGroupBy() || orderByColumns.contains(column))) {
                continue;
            }
            String name = cleanIdentifier(column.getName());
            if (name.matches("[A-Za-z0-9_$#]+")) {
                columns.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return new ArrayList<String>(columns);
    }

    private String cleanIdentifier(String value) {
        return value == null ? "" : value.replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
    }

    private String normalizeText(String value) {
        String cleaned = cleanIdentifier(value == null ? "" : value)
                .replaceAll("(?is)'([^']|'')*'", "?")
                .replaceAll("(?is)\\b\\d+(\\.\\d+)?\\b", "?")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        return cleaned;
    }

    private boolean containsWord(String value, String word) {
        return Pattern.compile("(?is)\\b" + word + "\\b").matcher(value).find();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
