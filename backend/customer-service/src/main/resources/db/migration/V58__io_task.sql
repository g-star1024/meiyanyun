-- ============================================================
-- V58 导入导出任务表（M3-B5 / DESIGN-M3 §3 M3-16：导入导出切真）
-- 单表：io_task（导入校验任务＋导出下载任务双类型，历史列表直读本表）
-- 说明：flyway_schema_history 全库共享，版本号全局递增（全局最大 V57 后取 V58；
--   号段定案 2026-09-27 B5 开工拍：DESIGN 原规划 V57=io_task 被 marketing
--   V57 follow_task_source_segment 占号→压缩后续号段顺移一位，B6/B8 顺延）。
-- 幂等可重入：CREATE TABLE / CREATE INDEX 均带 IF NOT EXISTS。
-- 不建物理外键：store_code 为逻辑引用（见列 COMMENT）。
-- 导入幂等：file_hash 部分唯一索引（照 V52 uk_touch_event_client_token_type 先例），
--   file_hash = 上传文件 SHA-256，重复文件撞唯一约束 → 409 携原任务单号，不重复建单。
-- ddl-auto=validate 硬约束（B48 卡3 收口）：本 DDL 与 IoTask 实体映射逐列对齐。
-- ============================================================

CREATE TABLE IF NOT EXISTS io_task (
    id             BIGSERIAL    PRIMARY KEY,
    task_no        VARCHAR(32)  NOT NULL UNIQUE,
    type           VARCHAR(8)   NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    scope          VARCHAR(16),
    file_name      VARCHAR(255),
    file_hash      VARCHAR(64),
    total_count    INTEGER      NOT NULL DEFAULT 0,
    success_count  INTEGER      NOT NULL DEFAULT 0,
    fail_count     INTEGER      NOT NULL DEFAULT 0,
    errors         JSONB,
    mask_phone     BOOLEAN      NOT NULL DEFAULT TRUE,
    mask_id_card   BOOLEAN      NOT NULL DEFAULT TRUE,
    store_code     VARCHAR(16),
    created_by     VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_io_task_type   CHECK (type IN ('IMPORT', 'EXPORT')),
    CONSTRAINT chk_io_task_status CHECK (status IN ('PENDING', 'VALIDATING', 'DONE', 'FAILED')),
    CONSTRAINT chk_io_task_scope  CHECK (scope IN ('ALL', 'TAG', 'LEVEL', 'SEGMENT'))
);

COMMENT ON TABLE  io_task IS '导入导出任务（M3-B5 / DESIGN-M3 §3 M3-16）：IMPORT 导入校验任务（同步校验流，仅校验不写 customer 表）＋EXPORT 导出下载任务（四 scope 过滤＋强制脱敏＋EasyExcel 附件流）；导入导出页历史列表/KPI 直读本表';
COMMENT ON COLUMN io_task.task_no IS '任务单号：IOT+yyyyMMdd-6位当日序号，DB 当日最大号+1（禁内存序列，照 BizNoGenerator 口径）';
COMMENT ON COLUMN io_task.type IS '任务类型二值：IMPORT 导入 / EXPORT 导出';
COMMENT ON COLUMN io_task.status IS '状态四态：PENDING 待校验 / VALIDATING 校验中 / DONE 已完成 / FAILED 失败（导入：全部行校验通过=DONE，存在失败行=FAILED；导出：文件生成成功=DONE）';
COMMENT ON COLUMN io_task.scope IS '导出范围四值（仅 EXPORT 填，IMPORT 为 NULL）：ALL 全部客户 / TAG 按标签（高意向） / LEVEL 按等级（金卡以上） / SEGMENT 按分群（高价值分群命中）';
COMMENT ON COLUMN io_task.file_name IS '文件名：IMPORT=上传原始文件名；EXPORT=生成文件名（客户导出-yyyyMMdd-HHmmss.xlsx）';
COMMENT ON COLUMN io_task.file_hash IS '导入文件 SHA-256（仅 IMPORT 填）：部分唯一索引幂等，重复文件 409 携原 task_no 不重复建单；EXPORT 为 NULL 不参与';
COMMENT ON COLUMN io_task.total_count IS '总行数：IMPORT=文件解析总行数；EXPORT=导出客户数';
COMMENT ON COLUMN io_task.success_count IS '成功行数：IMPORT=校验通过行数；EXPORT=导出客户数（同 total_count）';
COMMENT ON COLUMN io_task.fail_count IS '失败行数：IMPORT=校验失败行数（行级 errors）；EXPORT=恒 0';
COMMENT ON COLUMN io_task.errors IS '行级错误 JSONB 数组（仅 IMPORT）：[{row,name,phone,reason}]，reason 人类可读（如「手机号格式错误」）；EXPORT 为 NULL';
COMMENT ON COLUMN io_task.mask_phone IS '导出手机号脱敏标记（仅 EXPORT）：强制读 m3_settings.maskPhoneInExport（缺省 true），true=138****5678；IMPORT 恒 true 无义';
COMMENT ON COLUMN io_task.mask_id_card IS '导出身份证脱敏标记（仅 EXPORT 记录任务参数）：customer 表无身份证列，当前导出 11 列不含身份证，本列仅留存操作人意图；IMPORT 恒 true 无义';
COMMENT ON COLUMN io_task.store_code IS '门店编码：NULL=全连锁，填值=操作人门店（铁律-1-D；STORE 域导出按 DataScope 门店裁剪，本列留痕操作域）';
COMMENT ON COLUMN io_task.created_by IS '操作人（DataScope.currentActor()）';
COMMENT ON COLUMN io_task.created_at IS '创建时刻（历史列表展示列）';
COMMENT ON COLUMN io_task.updated_at IS '最近更新时刻（状态流转刷 now()）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_io_task_file_hash
    ON io_task (file_hash) WHERE file_hash IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_io_task_type_status ON io_task (type, status);
CREATE INDEX IF NOT EXISTS idx_io_task_created_at  ON io_task (created_at DESC);
