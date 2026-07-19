package com.codex.sqltuner.tuning.accuracy;

import com.codex.sqltuner.rule.SqlDialect;

import java.util.ArrayList;
import java.util.List;

public class SqlStatementProfile {
    private boolean valid;
    private String reason;
    private SqlDialect dialect;
    private String statementType;
    private List<String> tables = new ArrayList<String>();
    private boolean hasWhere;
    private boolean hasJoin;
    private boolean hasGroupBy;
    private boolean hasOrderBy;
    private boolean hasPagination;
    private boolean multiStatement;
    private boolean ddl;
    private List<String> wherePredicates = new ArrayList<String>();
    private List<String> joinSignatures = new ArrayList<String>();
    private List<String> groupByItems = new ArrayList<String>();
    private List<String> orderByItems = new ArrayList<String>();
    private List<String> paginationSignatures = new ArrayList<String>();
    private List<String> indexRelevantColumns = new ArrayList<String>();

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public SqlDialect getDialect() {
        return dialect;
    }

    public void setDialect(SqlDialect dialect) {
        this.dialect = dialect;
    }

    public String getStatementType() {
        return statementType;
    }

    public void setStatementType(String statementType) {
        this.statementType = statementType;
    }

    public List<String> getTables() {
        return tables;
    }

    public void setTables(List<String> tables) {
        this.tables = tables;
    }

    public boolean isHasWhere() {
        return hasWhere;
    }

    public void setHasWhere(boolean hasWhere) {
        this.hasWhere = hasWhere;
    }

    public boolean isHasJoin() {
        return hasJoin;
    }

    public void setHasJoin(boolean hasJoin) {
        this.hasJoin = hasJoin;
    }

    public boolean isHasGroupBy() {
        return hasGroupBy;
    }

    public void setHasGroupBy(boolean hasGroupBy) {
        this.hasGroupBy = hasGroupBy;
    }

    public boolean isHasOrderBy() {
        return hasOrderBy;
    }

    public void setHasOrderBy(boolean hasOrderBy) {
        this.hasOrderBy = hasOrderBy;
    }

    public boolean isHasPagination() {
        return hasPagination;
    }

    public void setHasPagination(boolean hasPagination) {
        this.hasPagination = hasPagination;
    }

    public boolean isMultiStatement() {
        return multiStatement;
    }

    public void setMultiStatement(boolean multiStatement) {
        this.multiStatement = multiStatement;
    }

    public boolean isDdl() {
        return ddl;
    }

    public void setDdl(boolean ddl) {
        this.ddl = ddl;
    }

    public List<String> getWherePredicates() {
        return wherePredicates;
    }

    public void setWherePredicates(List<String> wherePredicates) {
        this.wherePredicates = wherePredicates;
    }

    public List<String> getJoinSignatures() {
        return joinSignatures;
    }

    public void setJoinSignatures(List<String> joinSignatures) {
        this.joinSignatures = joinSignatures;
    }

    public List<String> getGroupByItems() {
        return groupByItems;
    }

    public void setGroupByItems(List<String> groupByItems) {
        this.groupByItems = groupByItems;
    }

    public List<String> getOrderByItems() {
        return orderByItems;
    }

    public void setOrderByItems(List<String> orderByItems) {
        this.orderByItems = orderByItems;
    }

    public List<String> getPaginationSignatures() {
        return paginationSignatures;
    }

    public void setPaginationSignatures(List<String> paginationSignatures) {
        this.paginationSignatures = paginationSignatures;
    }

    public List<String> getIndexRelevantColumns() {
        return indexRelevantColumns;
    }

    public void setIndexRelevantColumns(List<String> indexRelevantColumns) {
        this.indexRelevantColumns = indexRelevantColumns == null
                ? new ArrayList<String>()
                : indexRelevantColumns;
    }

}
