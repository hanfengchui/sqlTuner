package com.codex.sqltuner.tuning.accuracy;

import com.codex.sqltuner.rule.RuleFinding;
import com.codex.sqltuner.tuning.SqlTuningTask;
import com.codex.sqltuner.tuning.result.ContextAssessment;
import com.codex.sqltuner.tuning.result.EvidenceItem;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ContextAssessor {
    public ContextPackage assess(SqlTuningTask task, SqlStatementProfile profile, List<RuleFinding> findings) {
        ContextPackage context = new ContextPackage();
        ContextAssessment assessment = new ContextAssessment();
        context.setAssessment(assessment);

        addEvidence(context, "E_SQL", "USER_SQL", "已提供脱敏后的单条 " + profile.getStatementType() + " SQL", "HIGH");
        assessment.getAvailableEvidence().add("E_SQL");
        if (hasText(task.getSchemaText())) {
            addEvidence(context, "E_SCHEMA", "USER_SCHEMA", "用户提供表结构，长度 " + task.getSchemaText().length(), "MEDIUM");
            assessment.getAvailableEvidence().add("E_SCHEMA");
        } else {
            assessment.getMissingInformation().add("表结构 DDL");
        }
        if (hasText(task.getIndexText())) {
            addEvidence(context, "E_INDEX", "USER_INDEX", "用户提供现有索引，长度 " + task.getIndexText().length(), "MEDIUM");
            assessment.getAvailableEvidence().add("E_INDEX");
        } else {
            assessment.getMissingInformation().add("现有索引定义");
        }
        if (hasText(task.getExplainText())) {
            addEvidence(context, "E_EXPLAIN", "USER_EXPLAIN", "用户提供执行计划，长度 " + task.getExplainText().length(), "MEDIUM");
            assessment.getAvailableEvidence().add("E_EXPLAIN");
        } else {
            assessment.getMissingInformation().add("EXPLAIN / 执行计划");
        }
        if (hasReadablePlanImageFacts(task.getPlanImageFacts())) {
            addEvidence(context, "E_PLAN_IMAGE", "VISION_PLAN_IMAGE", "用户上传图片的视觉抽取摘要，低可信，仅用于辅助定位，不等同文本 EXPLAIN", "LOW");
            assessment.getAvailableEvidence().add("E_PLAN_IMAGE");
            assessment.getPolicyNotes().add("E_PLAN_IMAGE 来自图片视觉抽取，不能替代可解析 EXPLAIN，也不能提升 DDL/HIGH 置信度门禁");
        } else if (hasText(task.getPlanImageFacts())) {
            assessment.getPolicyNotes().add("上传图片无法可靠识别，未计入证据目录；请补充清晰截图或文本 EXPLAIN");
        }
        if (hasText(task.getTableStatsText())) {
            addEvidence(context, "E_STATS", "USER_STATS", "用户提供表统计信息，长度 " + task.getTableStatsText().length(), "MEDIUM");
            assessment.getAvailableEvidence().add("E_STATS");
        } else {
            assessment.getMissingInformation().add("表行数、列基数、数据分布");
        }
        if (hasText(task.getRuntimeMetricsText())) {
            addEvidence(context, "E_RUNTIME", "USER_RUNTIME", "用户提供运行指标，长度 " + task.getRuntimeMetricsText().length(), "MEDIUM");
            assessment.getAvailableEvidence().add("E_RUNTIME");
        }
        if (hasText(task.getObVersion())) {
            addEvidence(context, "E_OB_VERSION", "USER_VERSION", "OceanBase 版本: " + task.getObVersion(), "MEDIUM");
            assessment.getAvailableEvidence().add("E_OB_VERSION");
        } else {
            assessment.getMissingInformation().add("OceanBase 版本");
        }
        for (int i = 0; i < findings.size(); i++) {
            RuleFinding finding = findings.get(i);
            String id = "R_" + finding.getCode();
            addEvidence(context, id, "RULE", finding.getTitle() + ": " + finding.getEvidence(), finding.getSeverity().name());
            assessment.getAvailableEvidence().add(id);
        }

        boolean hasSchema = hasText(task.getSchemaText());
        boolean hasIndex = hasText(task.getIndexText());
        boolean hasExplain = hasText(task.getExplainText());
        boolean hasStats = hasText(task.getTableStatsText());
        boolean hasVersion = hasText(task.getObVersion());
        context.setAllowRewrite(hasSchema);
        context.setAllowIndexDirection(hasSchema && hasIndex);
        context.setAllowIndexDdl(hasSchema && hasIndex && hasExplain && hasStats && hasVersion);
        context.setAllowHighConfidence(context.isAllowIndexDdl());

        if (!hasSchema) {
            assessment.setCompleteness("SQL_ONLY");
            assessment.setMaxConfidence("LOW");
            assessment.getPolicyNotes().add("仅 SQL 场景禁止输出确定性改写和索引 DDL");
        } else if (!hasIndex) {
            assessment.setCompleteness("SQL_SCHEMA");
            assessment.setMaxConfidence("LOW");
            assessment.getPolicyNotes().add("有表结构但缺少索引，只允许带前提的改写候选");
        } else if (!context.isAllowIndexDdl()) {
            assessment.setCompleteness("SQL_SCHEMA_INDEX");
            assessment.setMaxConfidence("MEDIUM");
            assessment.getPolicyNotes().add("缺少 EXPLAIN/统计/版本时，索引建议最高 MEDIUM 且禁止候选 DDL");
        } else {
            assessment.setCompleteness("FULL");
            assessment.setMaxConfidence("HIGH");
            assessment.getPolicyNotes().add("证据完整，可输出可验证候选 DDL");
        }
        return context;
    }

    private void addEvidence(ContextPackage context, String id, String source, String summary, String trustLevel) {
        EvidenceItem evidence = new EvidenceItem();
        evidence.setId(id);
        evidence.setSource(source);
        evidence.setSummary(summary);
        evidence.setTrustLevel(trustLevel);
        context.getEvidenceCatalog().add(evidence);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean hasReadablePlanImageFacts(String value) {
        return hasText(value) && value.trim().startsWith("readable=true");
    }
}
