package com.codex.sqltuner.tuning.accuracy;

import com.codex.sqltuner.tuning.TuningResult;
import com.codex.sqltuner.tuning.result.IndexCandidate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class IndexCandidateValidator {
    private static final Pattern CREATE_INDEX_DEFINITION = Pattern.compile(
            "\\bcreate\\s+(?:unique\\s+)?index\\s+[`\"a-zA-Z0-9_$#.]+\\s+on\\s+"
                    + "([`\"a-zA-Z0-9_$#.]+)\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SHOW_INDEX_TABLE = Pattern.compile(
            "\\bshow\\s+(?:index|indexes|keys)\\s+(?:from|in)\\s+([`\"a-zA-Z0-9_$#.]+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CREATE_TABLE_START = Pattern.compile(
            "\\bcreate\\s+table\\s+([`\"a-zA-Z0-9_$#.]+)\\s*\\(",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern INLINE_INDEX_DEFINITION = Pattern.compile(
            "(?:^|[,;\\r\\n])\\s*(?:(?:constraint\\s+[`\"a-zA-Z0-9_$#.]+\\s+)?primary\\s+key|"
                    + "(?:unique\\s+)?(?:key|index)(?:\\s+[`\"a-zA-Z0-9_$#.]+)?)\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern INDEX_COLUMN = Pattern.compile(
            "^\\s*([`\"a-zA-Z0-9_$#.]+)(?:\\s+(ASC|DESC))?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final EvidenceReferenceValidator evidenceReferenceValidator;
    private final ResultTextSafetyValidator textSafetyValidator;
    private final SqlSemanticValidator sqlSemanticValidator;

    IndexCandidateValidator(EvidenceReferenceValidator evidenceReferenceValidator,
                            ResultTextSafetyValidator textSafetyValidator,
                            SqlSemanticValidator sqlSemanticValidator) {
        this.evidenceReferenceValidator = evidenceReferenceValidator;
        this.textSafetyValidator = textSafetyValidator;
        this.sqlSemanticValidator = sqlSemanticValidator;
    }

    void validate(TuningResult result,
                  ContextPackage context,
                  SqlStatementProfile originalProfile,
                  Set<String> evidenceIds,
                  ValidationOutcome outcome) {
        for (IndexCandidate candidate : result.getIndexCandidates()) {
            if (!ResultTextSafetyValidator.hasText(candidate.getTableName()) || candidate.getColumnOrder().isEmpty()) {
                outcome.reject("indexCandidates 缺少 tableName/columnOrder");
            }
            evidenceReferenceValidator.validateRefs("indexCandidates", candidate.getEvidenceRefs(), evidenceIds, outcome);
            textSafetyValidator.validateClaimText("indexCandidates", ResultTextSafetyValidator.combine(
                    candidate.getBenefit(), candidate.getWriteCost(), candidate.getRisk(), candidate.getValidation()),
                    context, outcome);
            IndexDefinition candidateDdl = validateCandidateDdl(candidate, outcome);
            List<String> candidateColumns = candidateDdl == null
                    ? normalizeColumns(candidate.getColumnOrder())
                    : candidateDdl.columns;
            String candidateTable = candidateDdl == null
                    ? SqlSemanticValidator.normalizeIdentifier(candidate.getTableName())
                    : candidateDdl.table;
            if (candidateColumns.isEmpty()) {
                outcome.reject("indexCandidates.columnOrder 包含非法或不可识别列名");
            }
            if (context.isRestrictIndexDirectionToSql()) {
                sqlSemanticValidator.validateIndexDirectionReferencesSql(
                        candidateTable, candidateColumns, originalProfile, outcome);
            }
            if (isCoveredByExistingIndex(candidateTable, candidateColumns, context.getIndexText(), originalProfile)) {
                outcome.reject("indexCandidates 与现有索引前缀重复: "
                        + SqlSemanticValidator.safeIdentifier(candidate.getTableName()));
            }
            if (!context.isAllowIndexDdl() && ResultTextSafetyValidator.hasText(candidate.getDdl())) {
                outcome.reject("证据不足时禁止输出候选索引 DDL");
            }
        }
    }

    private IndexDefinition validateCandidateDdl(IndexCandidate candidate, ValidationOutcome outcome) {
        if (candidate == null || !ResultTextSafetyValidator.hasText(candidate.getDdl())) {
            return null;
        }
        Matcher matcher = CREATE_INDEX_DEFINITION.matcher(candidate.getDdl());
        if (!matcher.find()) {
            outcome.reject("indexCandidates.ddl 必须是单条 CREATE INDEX");
            return null;
        }
        String remainder = (candidate.getDdl().substring(0, matcher.start())
                + candidate.getDdl().substring(matcher.end())).replace(";", "").trim();
        String ddlTable = matcher.group(1);
        String ddlColumns = matcher.group(2);
        if (!remainder.isEmpty() || matcher.find()) {
            outcome.reject("indexCandidates.ddl 只能包含单条 CREATE INDEX");
            return null;
        }
        IndexDefinition definition = indexDefinition(ddlTable, ddlColumns);
        List<String> metadataColumns = normalizeColumns(candidate.getColumnOrder());
        if (definition == null
                || !definition.table.equals(SqlSemanticValidator.normalizeIdentifier(candidate.getTableName()))
                || !definition.columns.equals(metadataColumns)) {
            outcome.reject("indexCandidates DDL 与 tableName/columnOrder 不一致");
            return null;
        }
        return definition;
    }

    private boolean isCoveredByExistingIndex(String candidateTable,
                                             List<String> candidateColumns,
                                             String indexText,
                                             SqlStatementProfile originalProfile) {
        if (!ResultTextSafetyValidator.hasText(candidateTable)
                || candidateColumns == null || candidateColumns.isEmpty()
                || !ResultTextSafetyValidator.hasText(indexText)) {
            return false;
        }
        for (IndexDefinition existing : existingIndexDefinitions(indexText)) {
            if (!sameIndexTable(candidateTable, existing.table, originalProfile)) {
                continue;
            }
            if (startsWith(existing.columns, candidateColumns)) {
                return true;
            }
        }
        return false;
    }

    private List<IndexDefinition> existingIndexDefinitions(String indexText) {
        List<IndexDefinition> definitions = new ArrayList<IndexDefinition>();
        Matcher standalone = CREATE_INDEX_DEFINITION.matcher(indexText);
        while (standalone.find()) {
            IndexDefinition definition = indexDefinition(standalone.group(1), standalone.group(2));
            if (definition != null) {
                definitions.add(definition);
            }
        }
        definitions.addAll(showIndexDefinitions(indexText));

        boolean scopedInlineFound = false;
        Matcher tableMatcher = CREATE_TABLE_START.matcher(indexText);
        while (tableMatcher.find()) {
            int open = tableMatcher.end() - 1;
            int close = matchingParenthesis(indexText, open);
            if (close < 0) {
                continue;
            }
            Matcher inline = INLINE_INDEX_DEFINITION.matcher(indexText.substring(open + 1, close));
            while (inline.find()) {
                IndexDefinition definition = indexDefinition(tableMatcher.group(1), inline.group(1));
                if (definition != null) {
                    definitions.add(definition);
                    scopedInlineFound = true;
                }
            }
        }

        if (!scopedInlineFound) {
            Matcher inline = INLINE_INDEX_DEFINITION.matcher(indexText);
            while (inline.find()) {
                IndexDefinition definition = indexDefinition("", inline.group(1));
                if (definition != null) {
                    definitions.add(definition);
                }
            }
        }
        return definitions;
    }

    private List<IndexDefinition> showIndexDefinitions(String indexText) {
        List<IndexDefinition> definitions = new ArrayList<IndexDefinition>();
        String fallbackTable = "";
        Matcher showIndex = SHOW_INDEX_TABLE.matcher(indexText);
        if (showIndex.find()) {
            fallbackTable = SqlSemanticValidator.normalizeIdentifier(showIndex.group(1));
        }

        String[] lines = indexText.split("\\r?\\n");
        List<String> headers = null;
        int tableColumn = -1;
        int keyColumn = -1;
        int sequenceColumn = -1;
        int nameColumn = -1;
        int collationColumn = -1;
        Map<String, TreeMap<Integer, String>> grouped = new LinkedHashMap<String, TreeMap<Integer, String>>();
        Map<String, String> groupedTables = new LinkedHashMap<String, String>();
        for (String line : lines) {
            List<String> cells = tableCells(line);
            if (cells.isEmpty()) {
                continue;
            }
            if (headers == null) {
                List<String> normalizedHeaders = normalizedHeaders(cells);
                keyColumn = normalizedHeaders.indexOf("keyname");
                sequenceColumn = normalizedHeaders.indexOf("seqinindex");
                nameColumn = normalizedHeaders.indexOf("columnname");
                if (keyColumn < 0 || sequenceColumn < 0 || nameColumn < 0) {
                    continue;
                }
                headers = normalizedHeaders;
                tableColumn = headers.indexOf("table");
                collationColumn = headers.indexOf("collation");
                continue;
            }
            int requiredMax = Math.max(keyColumn, Math.max(sequenceColumn, nameColumn));
            if (cells.size() <= requiredMax || isSeparatorRow(cells)) {
                continue;
            }
            Integer sequence = positiveInteger(cells.get(sequenceColumn));
            String keyName = SqlSemanticValidator.normalizeIdentifier(cells.get(keyColumn));
            String columnName = SqlSemanticValidator.normalizeIdentifier(cells.get(nameColumn));
            String table = tableColumn >= 0 && cells.size() > tableColumn
                    ? SqlSemanticValidator.normalizeIdentifier(cells.get(tableColumn))
                    : fallbackTable;
            if (sequence == null || keyName.isEmpty() || columnName.isEmpty()
                    || !columnName.matches("[a-z0-9_$#]+")) {
                continue;
            }
            String direction = "asc";
            if (collationColumn >= 0 && cells.size() > collationColumn
                    && "d".equalsIgnoreCase(cells.get(collationColumn).trim())) {
                direction = "desc";
            }
            String groupKey = table + "\u0000" + keyName;
            TreeMap<Integer, String> columns = grouped.get(groupKey);
            if (columns == null) {
                columns = new TreeMap<Integer, String>();
                grouped.put(groupKey, columns);
                groupedTables.put(groupKey, table);
            }
            columns.put(sequence, columnName + " " + direction);
        }
        for (Map.Entry<String, TreeMap<Integer, String>> entry : grouped.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                definitions.add(new IndexDefinition(groupedTables.get(entry.getKey()),
                        new ArrayList<String>(entry.getValue().values())));
            }
        }
        return definitions;
    }

    private List<String> tableCells(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty() || trimmed.matches("^[+|\\-: ]+$")) {
            return new ArrayList<String>();
        }
        String[] raw;
        if (trimmed.indexOf('|') >= 0) {
            raw = trimmed.replaceFirst("^\\|", "").replaceFirst("\\|$", "").split("\\s*\\|\\s*", -1);
        } else if (trimmed.indexOf('\t') >= 0) {
            raw = trimmed.split("\\t+", -1);
        } else {
            raw = trimmed.split("\\s{2,}", -1);
        }
        List<String> cells = new ArrayList<String>();
        for (String value : raw) {
            cells.add(value.trim());
        }
        return cells;
    }

    private List<String> normalizedHeaders(List<String> cells) {
        List<String> headers = new ArrayList<String>();
        for (String cell : cells) {
            headers.add(cell.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
        }
        return headers;
    }

    private boolean isSeparatorRow(List<String> cells) {
        for (String cell : cells) {
            if (!cell.matches("^[-+: ]*$")) {
                return false;
            }
        }
        return true;
    }

    private Integer positiveInteger(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private IndexDefinition indexDefinition(String table, String columnsText) {
        List<String> columns = normalizeColumns(splitColumns(columnsText));
        if (columns.isEmpty()) {
            return null;
        }
        return new IndexDefinition(SqlSemanticValidator.normalizeIdentifier(table), columns);
    }

    private boolean sameIndexTable(String candidateTable,
                                   String existingTable,
                                   SqlStatementProfile originalProfile) {
        if (candidateTable.equals(existingTable)) {
            return true;
        }
        if (!existingTable.isEmpty() || originalProfile == null || originalProfile.getTables().size() != 1) {
            return false;
        }
        return candidateTable.equals(SqlSemanticValidator.normalizeIdentifier(originalProfile.getTables().get(0)));
    }

    private int matchingParenthesis(String value, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < value.length(); i++) {
            char current = value.charAt(i);
            if (quote != 0) {
                if (current == quote) {
                    if (i + 1 < value.length() && value.charAt(i + 1) == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                } else if (current == '\\' && i + 1 < value.length()) {
                    i++;
                }
                continue;
            }
            if (current == '\'' || current == '\"' || current == '`') {
                quote = current;
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private List<String> splitColumns(String value) {
        List<String> columns = new ArrayList<String>();
        if (!ResultTextSafetyValidator.hasText(value)) {
            return columns;
        }
        for (String column : value.split(",")) {
            columns.add(column);
        }
        return columns;
    }

    private List<String> normalizeColumns(Iterable<String> values) {
        List<String> columns = new ArrayList<String>();
        if (values == null) {
            return columns;
        }
        for (String value : values) {
            if (!ResultTextSafetyValidator.hasText(value)) {
                return new ArrayList<String>();
            }
            Matcher columnMatcher = INDEX_COLUMN.matcher(value);
            if (!columnMatcher.matches()) {
                return new ArrayList<String>();
            }
            String normalized = SqlSemanticValidator.normalizeIdentifier(columnMatcher.group(1));
            if (!normalized.matches("[a-z0-9_$#]+")) {
                return new ArrayList<String>();
            }
            String direction = columnMatcher.group(2) == null
                    ? "asc"
                    : columnMatcher.group(2).toLowerCase(Locale.ROOT);
            columns.add(normalized + " " + direction);
        }
        return columns;
    }

    private boolean startsWith(List<String> existing, List<String> candidate) {
        if (existing.size() < candidate.size()) {
            return false;
        }
        for (int i = 0; i < candidate.size(); i++) {
            if (!existing.get(i).equals(candidate.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static final class IndexDefinition {
        private final String table;
        private final List<String> columns;

        private IndexDefinition(String table, List<String> columns) {
            this.table = table;
            this.columns = columns;
        }
    }
}
