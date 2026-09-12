-- =============================================================================
-- V18__ai_eval_experiment.sql
-- B45 AI 治理三期：效果评估任务 + A/B 模型实验
--
-- 版本链：全库共享 flyway_schema_history，V15/V16/V17 为 ai-service，本脚本占用 V18。
-- DDL 全部 CREATE TABLE IF NOT EXISTS，可在历史 ddl-auto=update 环境重入。
--
-- 数据真相约定：
--   评估与实验的技术指标（调用量/成功率/P99/token/成本）一律来自 append-only 的
--   ai_invoke_log，创建任务或刷新时把当时窗口聚合值快照写入 *_metrics 列（JSON）；
--   ai_invoke_log 无曝光/转化等业务字段，业务结论由运营人工写入 conclusion。
--
-- 表清单：
--   ai_eval_task   效果评估任务（按功能 FEATURE 或模型 MODEL × 近 N 天窗口聚合快照）
--   ai_experiment  A/B 实验（对照/实验两个模型同窗口指标对比，自动算提升百分点）
-- =============================================================================

-- 1. 效果评估任务 -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_eval_task (
    task_id        BIGSERIAL    PRIMARY KEY,
    task_name      VARCHAR(128) NOT NULL,
    eval_scope     VARCHAR(16)  NOT NULL,
    target_code    VARCHAR(128) NOT NULL,
    target_name    VARCHAR(128) NOT NULL,
    window_days    INTEGER      NOT NULL DEFAULT 7,
    period_start   TIMESTAMPTZ  NOT NULL,
    period_end     TIMESTAMPTZ  NOT NULL,
    metrics_json   TEXT         NOT NULL DEFAULT '{}',
    status         VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
    conclusion     VARCHAR(512),
    created_by     VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_eval_scope  CHECK (eval_scope IN ('FEATURE', 'MODEL')),
    CONSTRAINT chk_ai_eval_status CHECK (status IN ('RUNNING', 'DONE')),
    CONSTRAINT chk_ai_eval_days   CHECK (window_days BETWEEN 1 AND 90)
);
COMMENT ON TABLE  ai_eval_task IS 'AI 效果评估任务（技术指标快照自 ai_invoke_log，业务结论人工录入）';
COMMENT ON COLUMN ai_eval_task.eval_scope   IS '评估维度：FEATURE 按功能编码 / MODEL 按模型编码';
COMMENT ON COLUMN ai_eval_task.target_code  IS '维度目标编码：feature_code 或 model_code';
COMMENT ON COLUMN ai_eval_task.window_days  IS '评估窗口天数（1~90），创建时按北京时间回看';
COMMENT ON COLUMN ai_eval_task.metrics_json IS '指标快照 JSON：calls/successCalls/successRate/p99LatencyMs/tokens/costFen';
COMMENT ON COLUMN ai_eval_task.status       IS 'RUNNING 观察中（可刷新快照）/ DONE 已出结论（快照冻结）';
COMMENT ON COLUMN ai_eval_task.conclusion   IS '人工评估结论（转化率等业务口径无系统数据源，须人工填写）';

-- 2. A/B 模型实验 -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_experiment (
    experiment_id    BIGSERIAL    PRIMARY KEY,
    experiment_name  VARCHAR(128) NOT NULL,
    control_model    VARCHAR(128) NOT NULL,
    experiment_model VARCHAR(128) NOT NULL,
    window_days      INTEGER      NOT NULL DEFAULT 7,
    period_start     TIMESTAMPTZ  NOT NULL,
    period_end       TIMESTAMPTZ  NOT NULL,
    control_metrics  TEXT         NOT NULL DEFAULT '{}',
    experiment_metrics TEXT       NOT NULL DEFAULT '{}',
    lift_pp          NUMERIC(8,2),
    status           VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
    conclusion       VARCHAR(512),
    created_by       VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_exp_status CHECK (status IN ('RUNNING', 'FINISHED')),
    CONSTRAINT chk_ai_exp_days   CHECK (window_days BETWEEN 1 AND 90),
    CONSTRAINT chk_ai_exp_models CHECK (control_model <> experiment_model)
);
COMMENT ON TABLE  ai_experiment IS 'AI A/B 模型实验（对照/实验模型同窗口调用侧指标对比，提升=成功率百分点差）';
COMMENT ON COLUMN ai_experiment.control_model    IS '对照组模型编码 model_code';
COMMENT ON COLUMN ai_experiment.experiment_model IS '实验组模型编码 model_code';
COMMENT ON COLUMN ai_experiment.control_metrics  IS '对照组指标快照 JSON';
COMMENT ON COLUMN ai_experiment.experiment_metrics IS '实验组指标快照 JSON';
COMMENT ON COLUMN ai_experiment.lift_pp          IS '实验组成功率 − 对照组成功率（百分点 pp），快照时自动计算';
COMMENT ON COLUMN ai_experiment.status           IS 'RUNNING 观察中（可刷新）/ FINISHED 已结案（快照冻结）';
