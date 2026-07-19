package com.codex.sqltuner.tuning.accuracy;

import com.codex.sqltuner.rule.SqlDialect;
import com.codex.sqltuner.skill.SkillVersion;
import com.codex.sqltuner.skill.SkillPromptPolicy;
import com.codex.sqltuner.tuning.SqlTuningTask;
import com.codex.sqltuner.tuning.input.ReportTextParser;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptCompilerSafetyTest {
    @Test
    void pastedReportAdviceIsExplicitlyUntrusted() {
        SkillVersion skill = new SkillVersion();
        skill.setName("oceanbase-sql-tuning");
        skill.setContent("按证据分析");

        String prompt = new PromptCompiler().systemPrompt(skill, SqlDialect.OB_ORACLE);

        assertThat(prompt)
                .contains("待核验主张")
                .contains("不能进入 evidenceRefs")
                .contains("只有截图而没有可解析计划文本时仍视为缺少文本 EXPLAIN")
                .contains("analysisNarrative 是用户真正阅读的主答案")
                .contains("先给最终判断")
                .contains("不要预测具体收益、百分比或耗时")
                .contains("截图显示、待文本 EXPLAIN 确认")
                .contains("不得使用 MySQL 专有 FILESORT")
                .contains("禁止建议把 ROWNUM 放到内层 ORDER BY 之前")
                .contains("不要仅因 SELECT 未投影右表列就建议删除 LEFT JOIN")
                .contains("不得直接包含完整改写 SQL 或 DDL")
                .contains("最终决策门禁")
                .contains("estimatedRows、cost、截图识别值都不是实际运行行数")
                .contains("已有索引单次探测行数接近 1")
                .contains("降至 10ms")
                .contains("任一项不通过");
    }

    @Test
    void oversizedAdministratorSkillIsRejectedBeforePromptCompilation() {
        SkillVersion skill = new SkillVersion();
        skill.setName("oceanbase-sql-tuning");
        skill.setContent(repeat('x', SkillPromptPolicy.MAX_CONTENT_CHARS + 1));

        assertThatThrownBy(() -> new PromptCompiler().systemPrompt(skill, SqlDialect.OB_MYSQL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("技能内容超过");
    }

    @Test
    void analysisPromptExcludesMetricsFoundOnlyInsideHistoricalClaims() {
        String sql = "SELECT id FROM orders WHERE tenant_id = ?";
        SqlTuningTask task = new SqlTuningTask();
        task.setSanitizedSql(sql);
        task.setRuntimeMetricsText("平均耗时: 2008ms\n平均返回行数"
                + ReportTextParser.UNVERIFIED_REPORT_METRIC_MARKER + ": 1 行");
        SqlStatementProfile profile = new SqlStatementParser().parse(sql, SqlDialect.OB_MYSQL);
        ContextPackage context = new ContextAssessor().assess(task, profile, Collections.emptyList());

        String prompt = new PromptCompiler().analysisPrompt(
                task, SqlDialect.OB_MYSQL, profile, context, Collections.emptyList(), "无");

        assertThat(prompt)
                .contains("运行指标（仅可引用已计入 E_RUNTIME 的直接指标）:\n平均耗时: 2008ms")
                .doesNotContain(ReportTextParser.UNVERIFIED_REPORT_METRIC_MARKER)
                .doesNotContain("平均返回行数: 1 行");
    }

    private String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
