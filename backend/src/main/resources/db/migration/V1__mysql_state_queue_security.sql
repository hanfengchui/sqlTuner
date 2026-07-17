CREATE TABLE users (
    id BIGINT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE conversations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    INDEX idx_conversations_user_updated (user_id, updated_at),
    CONSTRAINT fk_conversations_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL,
    task_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL,
    INDEX idx_messages_conversation_created (conversation_id, created_at),
    CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations(id)
);

CREATE TABLE tuning_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL,
    status_message VARCHAR(512) NULL,
    task_json JSON NOT NULL,
    queued_at TIMESTAMP NULL,
    lease_owner VARCHAR(128) NULL,
    lease_until TIMESTAMP NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NULL,
    last_error_code VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_tasks_user_idempotency (user_id, idempotency_key),
    INDEX idx_tasks_owner_status (user_id, status),
    INDEX idx_tasks_queue (status, next_attempt_at, queued_at),
    INDEX idx_tasks_lease (lease_until),
    CONSTRAINT fk_tasks_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_tasks_conversation FOREIGN KEY (conversation_id) REFERENCES conversations(id)
);

CREATE TABLE task_artifacts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    node_name VARCHAR(128) NOT NULL,
    summary VARCHAR(512) NOT NULL,
    payload_json JSON NULL,
    created_at TIMESTAMP NOT NULL,
    INDEX idx_artifacts_task (task_id),
    CONSTRAINT fk_artifacts_task FOREIGN KEY (task_id) REFERENCES tuning_tasks(id)
);

CREATE TABLE skill_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    version INT NOT NULL,
    content LONGTEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_skill_name_version (name, version),
    INDEX idx_skill_active (name, enabled, version)
);

CREATE TABLE model_config (
    id BIGINT PRIMARY KEY,
    provider VARCHAR(64) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    model VARCHAR(128) NOT NULL,
    encrypted_api_key LONGTEXT NULL,
    timeout_ms INT NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE migration_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_sha256 VARCHAR(64) NOT NULL UNIQUE,
    source_path VARCHAR(1024) NOT NULL,
    dry_run BOOLEAN NOT NULL DEFAULT FALSE,
    imported_counts_json JSON NOT NULL,
    created_at TIMESTAMP NOT NULL
);
