CREATE TABLE queue_admission_lock (
    id TINYINT PRIMARY KEY,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO queue_admission_lock(id, updated_at) VALUES (1, CURRENT_TIMESTAMP);
