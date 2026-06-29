package com.codex.sqltuner.rule;

import java.util.Locale;

/**
 * 统一调优方言名称，避免前端别名或旧数据让规则和 Prompt 分支失配。
 * 这里只控制分析口径，不代表服务会连接真实数据库执行 SQL。
 */
public enum SqlDialect {
    OB_MYSQL("OceanBase MySQL"),
    OB_ORACLE("OceanBase Oracle");

    private final String displayName;

    SqlDialect(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static SqlDialect from(String value) {
        if (value == null || value.trim().isEmpty()) {
            return OB_MYSQL;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replace("_", " ")
                .replace("-", " ")
                .replace("/", " ");
        if (normalized.contains("oracle")) {
            return OB_ORACLE;
        }
        if (normalized.contains("mysql")) {
            return OB_MYSQL;
        }
        throw new IllegalArgumentException("暂不支持的数据库方言: " + value + "，当前支持 OceanBase MySQL / OceanBase Oracle");
    }
}
