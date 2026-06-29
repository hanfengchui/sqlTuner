package com.codex.sqltuner.storage;

import com.codex.sqltuner.conversation.Conversation;
import com.codex.sqltuner.conversation.Message;
import com.codex.sqltuner.skill.SkillVersion;
import com.codex.sqltuner.tuning.SqlTuningTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PersistentAppState {
    private long conversationSequence = 100;
    private long messageSequence = 1000;
    private long taskSequence = 1000;
    private long skillSequence = 1;
    private Map<Long, Conversation> conversations = new LinkedHashMap<Long, Conversation>();
    private Map<Long, List<Message>> messages = new LinkedHashMap<Long, List<Message>>();
    private Map<Long, SqlTuningTask> tasks = new LinkedHashMap<Long, SqlTuningTask>();
    private Map<String, SkillVersion> skills = new LinkedHashMap<String, SkillVersion>();
    private ModelConfigRecord modelConfig;

    public long getConversationSequence() {
        return conversationSequence;
    }

    public void setConversationSequence(long conversationSequence) {
        this.conversationSequence = conversationSequence;
    }

    public long getMessageSequence() {
        return messageSequence;
    }

    public void setMessageSequence(long messageSequence) {
        this.messageSequence = messageSequence;
    }

    public long getTaskSequence() {
        return taskSequence;
    }

    public void setTaskSequence(long taskSequence) {
        this.taskSequence = taskSequence;
    }

    public long getSkillSequence() {
        return skillSequence;
    }

    public void setSkillSequence(long skillSequence) {
        this.skillSequence = skillSequence;
    }

    public Map<Long, Conversation> getConversations() {
        return conversations;
    }

    public void setConversations(Map<Long, Conversation> conversations) {
        this.conversations = conversations;
    }

    public Map<Long, List<Message>> getMessages() {
        return messages;
    }

    public void setMessages(Map<Long, List<Message>> messages) {
        this.messages = messages;
    }

    public Map<Long, SqlTuningTask> getTasks() {
        return tasks;
    }

    public void setTasks(Map<Long, SqlTuningTask> tasks) {
        this.tasks = tasks;
    }

    public Map<String, SkillVersion> getSkills() {
        return skills;
    }

    public void setSkills(Map<String, SkillVersion> skills) {
        this.skills = skills;
    }

    public ModelConfigRecord getModelConfig() {
        return modelConfig;
    }

    public void setModelConfig(ModelConfigRecord modelConfig) {
        this.modelConfig = modelConfig;
    }

    public List<Conversation> conversationList() {
        return new ArrayList<Conversation>(conversations.values());
    }
}
