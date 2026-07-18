CREATE INDEX idx_conversations_retention ON conversations(updated_at, id);

CREATE INDEX idx_tasks_conversation_status ON tuning_tasks(conversation_id, status);
