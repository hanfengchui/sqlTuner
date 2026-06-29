package com.codex.sqltuner.rule;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

@Component
public class SqlSanitizer {
    private static final Pattern STRING_LITERAL = Pattern.compile("'([^']|'')*'");
    private static final Pattern PHONE = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\b\\d{8,}\\b");

    public SanitizedSql sanitize(String sql) {
        if (sql == null) {
            return new SanitizedSql("", "", 0, "empty");
        }
        String sanitized = STRING_LITERAL.matcher(sql).replaceAll("'***'");
        sanitized = PHONE.matcher(sanitized).replaceAll("***PHONE***");
        sanitized = EMAIL.matcher(sanitized).replaceAll("***EMAIL***");
        sanitized = LONG_NUMBER.matcher(sanitized).replaceAll("***NUM***");
        return new SanitizedSql(sql, sanitized, sql.length(), sha256(sql));
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
