package com.codex.sqltuner.skill;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class SaveSkillRequest {
    @NotBlank(message = "技能名称不能为空")
    @Size(max = SkillPromptPolicy.MAX_NAME_CHARS, message = "技能名称最多 128 字符")
    private String name;

    @NotBlank(message = "技能内容不能为空")
    @Size(max = SkillPromptPolicy.MAX_CONTENT_CHARS, message = "技能内容最多 16384 字符")
    private String content;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
