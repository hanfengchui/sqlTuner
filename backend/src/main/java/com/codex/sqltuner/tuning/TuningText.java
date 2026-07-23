package com.codex.sqltuner.tuning;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;

final class TuningText {
    private TuningText() {
    }

    static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', ' ').trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    static String abbreviateUtf8(String value, int maxBytes) {
        if (value == null || maxBytes <= 0) {
            return "";
        }
        String normalized = value.replace('\r', ' ').trim();
        if (normalized.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return normalized;
        }
        final String suffix = "...";
        int remaining = Math.max(0, maxBytes - suffix.getBytes(StandardCharsets.UTF_8).length);
        int end = 0;
        int used = 0;
        while (end < normalized.length()) {
            int codePoint = normalized.codePointAt(end);
            String codePointText = new String(Character.toChars(codePoint));
            int bytes = codePointText.getBytes(StandardCharsets.UTF_8).length;
            if (used + bytes > remaining) {
                break;
            }
            used += bytes;
            end += Character.charCount(codePoint);
        }
        return normalized.substring(0, end) + suffix;
    }

    static String joinNonEmpty(String... values) {
        StringBuilder builder = new StringBuilder();
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!hasText(value)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(value.trim());
        }
        return builder.toString();
    }

    static String joinDistinctNonEmpty(String... values) {
        LinkedHashSet<String> distinct = new LinkedHashSet<String>();
        if (values != null) {
            for (String value : values) {
                if (hasText(value)) {
                    distinct.add(value.trim());
                }
            }
        }
        return joinNonEmpty(distinct.toArray(new String[distinct.size()]));
    }

    static String firstNonEmpty(String left, String right) {
        return hasText(left) ? left : right;
    }

    static String firstNonEmpty(String... values) {
        if (values != null) {
            for (String value : values) {
                if (hasText(value)) {
                    return value.trim();
                }
            }
        }
        return null;
    }
}
