-- =============================================================================
-- V16__ai_invoke_log_error_code_extend.sql
-- B43 真实调用出口落地后修复：
--   ai_invoke_log.error_code 建表时为 VARCHAR(64)，仅够放短错误码；
--   失败链路实际写入的是上游错误消息（如 ARK 400 文本），64 位必然溢出导致
--   失败日志 INSERT 二次异常、接口退化为 500。扩展为 VARCHAR(512)。
-- 幂等：ALTER TYPE 对已是 512 的环境重跑会报 "type already is"，
--   故先 DO 块按 information_schema 判定，可安全重入。
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'ai_invoke_log'
          AND column_name = 'error_code'
          AND character_maximum_length < 512
    ) THEN
        ALTER TABLE ai_invoke_log ALTER COLUMN error_code TYPE VARCHAR(512);
    END IF;
END $$;
