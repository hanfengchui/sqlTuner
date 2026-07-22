package com.codex.sqltuner.rule;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.ast.expr.SQLDateExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLInListExpr;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.expr.SQLNumberExpr;
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLTimestampExpr;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

@Component
public class SqlSanitizer {
    private static final Pattern STRING_LITERAL = Pattern.compile("'([^']|'')*'");
    private static final Pattern DATE_LITERAL = Pattern.compile("^'\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}:\\d{2})?'$");
    private static final Pattern LIKE_LITERAL = Pattern.compile("(?is)like\\s*'([^']|'')*'");
    private static final Pattern IN_LIST = Pattern.compile("(?is)\\bin\\s*\\(([^()]{1,2000})\\)");
    private static final Pattern DECIMAL_NUMBER = Pattern.compile("(?<![\\w.])-?\\d+\\.\\d+(?![\\w.])");
    private static final Pattern INTEGER_NUMBER = Pattern.compile("(?<![\\w.])-?\\d+(?![\\w.])");
    private static final Pattern PHONE = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    public SanitizedSql sanitize(String sql) {
        return sanitize(sql, SqlDialect.OB_MYSQL);
    }

    public SanitizedSql sanitize(String sql, SqlDialect dialect) {
        if (sql == null) {
            return new SanitizedSql("", "", 0, "empty");
        }
        try {
            String dbType = dialect == SqlDialect.OB_ORACLE ? "oceanbase_oracle" : "oceanbase";
            java.util.List<SQLStatement> statements = SQLUtils.parseStatements(sql, dbType);
            if (statements.size() == 1) {
                SQLStatement statement = statements.get(0);
                statement.accept(new TypedLiteralMaskingVisitor());
                String sanitized = SQLUtils.toSQLString(statement, dbType);
                return new SanitizedSql(sql, sanitized, sql.length(), sha256(sql));
            }
        } catch (RuntimeException ignored) {
            // 输入校验会单独报告解析错误；这里保留结构化正则兜底，避免脱敏失败泄露字面量。
        } catch (StackOverflowError ignored) {
            // Druid 1.2.28 的 Oracle UPDATE AST 在特定带别名的 SET 结构上会递归溢出。
            // 脱敏不能让调优任务卡死，因此退回到保守的正则掩码。
        }
        return sanitizeWithPatterns(sql);
    }

    private SanitizedSql sanitizeWithPatterns(String sql) {
        String sanitized = EMAIL.matcher(sql).replaceAll("__EMAIL__");
        sanitized = PHONE.matcher(sanitized).replaceAll("__PHONE__");
        sanitized = sanitizeLike(sanitized);
        sanitized = sanitizeInLists(sanitized);
        sanitized = sanitizeStringLiterals(sanitized);
        sanitized = DECIMAL_NUMBER.matcher(sanitized).replaceAll("__NUM_DEC__");
        sanitized = INTEGER_NUMBER.matcher(sanitized).replaceAll("__NUM_INT__");
        return new SanitizedSql(sql, sanitized, sql.length(), sha256(sql));
    }

    private static final class TypedLiteralMaskingVisitor extends SQLASTVisitorAdapter {
        @Override
        public boolean visit(SQLInListExpr expression) {
            if (expression.getTargetList().isEmpty()) {
                return true;
            }
            for (SQLExpr target : expression.getTargetList()) {
                if (target instanceof SQLQueryExpr) {
                    return true;
                }
            }
            int count = expression.getTargetList().size();
            java.util.List<SQLExpr> masked = new java.util.ArrayList<SQLExpr>();
            masked.add(new SQLIdentifierExpr("__IN_LIST_" + count + "__"));
            expression.setTargetList(masked);
            return true;
        }

        @Override
        public boolean visit(SQLCharExpr expression) {
            String text = expression.getText() == null ? "" : expression.getText();
            if (isLikeOperand(expression)) {
                expression.setText(likeShape(text));
            } else if (text.matches("[-+]?\\d+(\\.\\d+)?")) {
                expression.setText("__STR_NUMERIC__");
            } else if (text.matches("\\d{4}-\\d{2}-\\d{2}([ T].*)?")) {
                expression.setText("__DATE_LITERAL__");
            } else {
                expression.setText("__STR_LITERAL__");
            }
            return false;
        }

        @Override
        public boolean visit(SQLDateExpr expression) {
            expression.setValue("__DATE_LITERAL__");
            return false;
        }

        @Override
        public boolean visit(SQLTimestampExpr expression) {
            expression.setValue("__TIMESTAMP_LITERAL__");
            return false;
        }

        @Override
        public boolean visit(SQLIntegerExpr expression) {
            SQLUtils.replaceInParent(expression, new SQLIdentifierExpr("__NUM_INT__"));
            return false;
        }

        @Override
        public boolean visit(SQLNumberExpr expression) {
            SQLUtils.replaceInParent(expression, new SQLIdentifierExpr("__NUM_DEC__"));
            return false;
        }

        private boolean isLikeOperand(SQLCharExpr expression) {
            if (!(expression.getParent() instanceof SQLBinaryOpExpr)) {
                return false;
            }
            SQLBinaryOpExpr parent = (SQLBinaryOpExpr) expression.getParent();
            return parent.getOperator() != null
                    && parent.getOperator().name().toLowerCase(java.util.Locale.ROOT).contains("like");
        }

        private String likeShape(String text) {
            if (text.startsWith("%") && text.endsWith("%") && text.length() > 1) {
                return "LIKE_BOTH_WILDCARD";
            }
            if (text.startsWith("%")) {
                return "LIKE_LEADING_WILDCARD";
            }
            if (text.endsWith("%")) {
                return "LIKE_TRAILING_WILDCARD";
            }
            return "LIKE_LITERAL";
        }
    }

    private String sanitizeLike(String sql) {
        java.util.regex.Matcher matcher = LIKE_LITERAL.matcher(sql);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String literal = matcher.group().substring(matcher.group().indexOf('\''));
            String unquoted = literal.substring(1, literal.length() - 1);
            String shape = "LIKE_LITERAL";
            if (unquoted.startsWith("%") && unquoted.endsWith("%") && unquoted.length() > 1) {
                shape = "LIKE_BOTH_WILDCARD";
            } else if (unquoted.startsWith("%")) {
                shape = "LIKE_LEADING_WILDCARD";
            } else if (unquoted.endsWith("%")) {
                shape = "LIKE_TRAILING_WILDCARD";
            }
            matcher.appendReplacement(buffer, "like '" + shape + "'");
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String sanitizeInLists(String sql) {
        java.util.regex.Matcher matcher = IN_LIST.matcher(sql);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String body = matcher.group(1);
            if (body.toLowerCase(java.util.Locale.ROOT).contains("select")) {
                matcher.appendReplacement(buffer, matcher.group());
                continue;
            }
            int count = countItems(body);
            matcher.appendReplacement(buffer, "in (__IN_LIST_" + count + "__)");
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String sanitizeStringLiterals(String sql) {
        java.util.regex.Matcher matcher = STRING_LITERAL.matcher(sql);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String literal = matcher.group();
            String placeholder;
            if (literal.contains("LIKE_")) {
                placeholder = literal;
            } else {
                String unquoted = literal.substring(1, literal.length() - 1).replace("''", "'");
                if (unquoted.matches("[-+]?\\d+(\\.\\d+)?")) {
                    placeholder = "'__STR_NUMERIC__'";
                } else {
                    placeholder = DATE_LITERAL.matcher(literal).matches() ? "'__DATE_LITERAL__'" : "'__STR_LITERAL__'";
                }
            }
            matcher.appendReplacement(buffer, placeholder);
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private int countItems(String body) {
        int count = 1;
        boolean inString = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'') {
                if (inString && i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    inString = !inString;
                }
            } else if (!inString && c == ',') {
                count++;
            }
        }
        return count;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < bytes.length && i < 8; i++) {
                builder.append(String.format("%02x", bytes[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }
}
