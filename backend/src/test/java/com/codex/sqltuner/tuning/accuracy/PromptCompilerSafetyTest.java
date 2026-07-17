package com.codex.sqltuner.tuning.accuracy;

import com.codex.sqltuner.rule.SqlDialect;
import com.codex.sqltuner.skill.SkillVersion;
import com.codex.sqltuner.skill.SkillPromptPolicy;
import org.junit.jupiter.api.Test;

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
                .contains("只有截图而没有可解析计划文本时仍视为缺少文本 EXPLAIN");
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

    private String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
