-- M3-B3 修复：follow_task.source CHECK 扩 'SEGMENT'（分群批量跟进下发通道）。
-- V55 建表时枚举仅 MANUAL/CHURN/REPURCHASE；B3 分群「一键跟进」经 customer 内部 client 下发 source=SEGMENT，
-- Java 侧 FollowTaskService.AI_SOURCES 已放行，DB 层 CHECK 同步放宽（放宽约束对存量数据零影响）。
ALTER TABLE follow_task DROP CONSTRAINT IF EXISTS chk_follow_task_source;
ALTER TABLE follow_task ADD CONSTRAINT chk_follow_task_source
    CHECK (source IN ('MANUAL', 'CHURN', 'REPURCHASE', 'SEGMENT'));

COMMENT ON COLUMN follow_task.source IS '任务来源：MANUAL=人工创建 / CHURN=流失干预 / REPURCHASE=复购关怀 / SEGMENT=分群批量跟进（M3-B3）';
