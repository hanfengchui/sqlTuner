ALTER TABLE model_config ADD COLUMN vision_model VARCHAR(128) NULL;

UPDATE model_config
SET vision_model = CASE
    WHEN LOWER(provider) = 'dashscope' THEN 'qwen3-vl-plus'
    ELSE model
END
WHERE vision_model IS NULL OR TRIM(vision_model) = '';
