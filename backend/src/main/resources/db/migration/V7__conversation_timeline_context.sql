ALTER TABLE conversations ADD COLUMN context_snapshot LONGTEXT NULL;

CREATE INDEX idx_conversations_user_updated_id ON conversations(user_id, updated_at, id);

CREATE INDEX idx_tasks_conversation_created ON tuning_tasks(conversation_id, created_at, id);
