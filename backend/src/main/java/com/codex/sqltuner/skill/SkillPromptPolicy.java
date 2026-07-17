package com.codex.sqltuner.skill;

public final class SkillPromptPolicy {
    public static final int MAX_NAME_CHARS = 128;
    public static final int MAX_CONTENT_CHARS = 16 * 1024;
    public static final int MAX_SYSTEM_PROMPT_CHARS = 24 * 1024;

    private SkillPromptPolicy() {
    }

    public static void requireValid(String name, String content) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("技能名称不能为空");
        }
        if (name.length() > MAX_NAME_CHARS) {
            throw new IllegalArgumentException("技能名称超过 " + MAX_NAME_CHARS + " 字符");
        }
        requireContentBudget(content);
    }

    public static void requireContentBudget(String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("技能内容不能为空");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("技能内容超过 " + MAX_CONTENT_CHARS + " 字符，拒绝发布以保护模型提示预算");
        }
    }
}
