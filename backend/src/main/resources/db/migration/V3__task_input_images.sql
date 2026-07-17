CREATE TABLE task_input_images (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    image_order INT NOT NULL,
    file_name VARCHAR(255) NULL,
    media_type VARCHAR(32) NOT NULL,
    byte_size INT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    image_data LONGBLOB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_task_input_images_order (task_id, image_order),
    INDEX idx_task_input_images_task (task_id),
    CONSTRAINT fk_task_input_images_task FOREIGN KEY (task_id) REFERENCES tuning_tasks(id) ON DELETE CASCADE
);
