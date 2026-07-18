package com.codex.sqltuner.tuning;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModelStreamProjector {
    private static final Pattern CONCLUSION = Pattern.compile("\"conclusion\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern SECTION_TITLE = Pattern.compile("\"title\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern SECTION_BODY = Pattern.compile("\"body\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern EXECUTABLE_SQL = Pattern.compile(
            "(?i)(?:```\\s*sql\\b|\\bsql\\s*:|\\b(?:select|with|insert|replace|update|delete|merge|create|alter|drop|"
                    + "truncate|rename|grant|revoke|call|exec|execute|begin|declare|explain|analyze|lock|set|commit|rollback)\\b)");
    private static final int MAX_DRAFT_LENGTH = 12 * 1024;
    private final ObjectMapper objectMapper;

    public ModelStreamProjector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String project(String accumulatedModelContent) {
        if (accumulatedModelContent == null || !accumulatedModelContent.contains("analysisNarrative")) {
            return "";
        }
        String narrative = narrativeSlice(accumulatedModelContent);
        List<PositionedText> matches = new ArrayList<PositionedText>();
        appendMatches(CONCLUSION, narrative, matches);
        appendMatches(SECTION_TITLE, narrative, matches);
        appendMatches(SECTION_BODY, narrative, matches);
        Collections.sort(matches, new Comparator<PositionedText>() {
            @Override
            public int compare(PositionedText left, PositionedText right) {
                return Integer.compare(left.position, right.position);
            }
        });
        List<String> parts = new ArrayList<String>();
        for (PositionedText match : matches) {
            if (parts.size() >= 9) {
                break;
            }
            parts.add(match.text);
        }
        return sanitize(String.join("\n\n", parts));
    }

    private String narrativeSlice(String value) {
        int start = value.indexOf("\"analysisNarrative\"");
        if (start < 0) {
            return "";
        }
        int end = value.length();
        String[] stopFields = new String[]{
                "\"contextAssessment\"", "\"evidenceCatalog\"", "\"diagnoses\"", "\"rewriteCandidates\"",
                "\"indexCandidates\"", "\"validationPlan\"", "\"missingInformation\"", "\"safetyWarnings\"",
                "\"review\"", "\"findings\"", "\"rewriteSql\"", "\"indexSuggestions\"", "\"rawModelOutput\""
        };
        for (String field : stopFields) {
            int index = value.indexOf(field, start + 1);
            if (index > start && index < end) {
                end = index;
            }
        }
        return value.substring(start, end);
    }

    private void appendMatches(Pattern pattern, String value, List<PositionedText> parts) {
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            String text = decodeJsonString(matcher.group(1));
            if (hasText(text)) {
                parts.add(new PositionedText(matcher.start(), text));
            }
        }
    }

    private String decodeJsonString(String escaped) {
        try {
            return objectMapper.readValue("\"" + escaped + "\"", String.class);
        } catch (IOException e) {
            return "";
        }
    }

    private String sanitize(String value) {
        if (!hasText(value)) {
            return "";
        }
        // 草稿是未校验内容。只要叙事中混入任何可执行 SQL 形态，就整段暂不展示，
        // 避免 CREATE UNIQUE/BITMAP INDEX 或跨行 SQL 绕过逐行过滤。
        if (EXECUTABLE_SQL.matcher(value).find()) {
            return "";
        }
        StringBuilder safe = new StringBuilder();
        for (String rawLine : value.split("\\r?\\n")) {
            String line = rawLine.trim();
            line = line.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
            if (!hasText(line)) {
                continue;
            }
            if (safe.length() > 0) {
                safe.append('\n');
            }
            safe.append(line);
            if (safe.length() >= MAX_DRAFT_LENGTH) {
                break;
            }
        }
        return safe.length() > MAX_DRAFT_LENGTH ? safe.substring(0, MAX_DRAFT_LENGTH) : safe.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static class PositionedText {
        private final int position;
        private final String text;

        private PositionedText(int position, String text) {
            this.position = position;
            this.text = text;
        }
    }
}
