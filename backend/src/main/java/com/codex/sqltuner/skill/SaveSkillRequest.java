package com.codex.sqltuner.skill;

import javax.validation.constraints.NotBlank;

public class SaveSkillRequest {
    @NotBlank(message = "技能名称不能为空")
    private String name;

    @NotBlank(message = "技能内容不能为空")
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
