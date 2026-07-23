package com.codex.sqltuner.tuning.input;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReportParsingSupport {
    private ReportParsingSupport() {
    }

    static int firstLabelStart(String text, int fromIndex, List<Pattern> labels) {
        int end = text.length();
        for (Pattern label : labels) {
            Matcher matcher = label.matcher(text);
            if (matcher.find(fromIndex) && matcher.start() < end) {
                end = matcher.start();
            }
        }
        return end;
    }

    static String cleanValue(String value) {
        return value.trim().replaceAll("[\\t ]+", " ");
    }

    static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    static String join(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    static String joinSections(List<String> sections) {
        StringBuilder builder = new StringBuilder();
        for (String section : sections) {
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(section);
        }
        return builder.toString();
    }

    static String joinNonEmpty(String left, String right) {
        if (!hasText(left)) {
            return right;
        }
        if (!hasText(right) || left.equals(right)) {
            return left;
        }
        return left + "\n" + right;
    }
}
