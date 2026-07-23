CREATE TABLE task_stage_results (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    stage_name VARCHAR(64) NOT NULL,
    input_sha256 VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    content LONGTEXT NOT NULL,
    elapsed_ms BIGINT NOT NULL,
    mock BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_task_stage_input (task_id, stage_name, input_sha256),
    INDEX idx_task_stage_results_task (task_id),
    CONSTRAINT fk_stage_results_task FOREIGN KEY (task_id) REFERENCES tuning_tasks(id)
);
