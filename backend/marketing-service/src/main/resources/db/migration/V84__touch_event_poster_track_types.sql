-- ============================================================
-- V84 触点事件 CHECK 扩列（棒⑥卡5 / L160② 三轨真验修复）
-- 背景：V52 chk_touch_event_type 仅放行五值（LANDING_VISIT/LANDING_LEAD/POSTER_SCAN/
--   PUSH_SEND/RETURNBACK），缺本卡新增 POSTER_SHARE/POSTER_LEAD/POSTER_VISIT 三触点；
--   track 上报撞 CHECK 被 Spring 包成 DataIntegrityViolationException，遭采集端点并发
--   幂等兜底吞掉误报 dedup=true（漏斗列不自增）——curl 轨真验逮出。
-- 做法：DROP CONSTRAINT IF EXISTS 后按八值重建（PG15 ADD CONSTRAINT 无 IF NOT EXISTS，
--   先 DROP 再 ADD 天然可重入）；COMMENT 同步八值口径。
-- ============================================================

ALTER TABLE touch_event DROP CONSTRAINT IF EXISTS chk_touch_event_type;
ALTER TABLE touch_event ADD CONSTRAINT chk_touch_event_type
    CHECK (touch_type IN ('LANDING_VISIT', 'LANDING_LEAD',
                          'POSTER_SHARE', 'POSTER_SCAN', 'POSTER_LEAD', 'POSTER_VISIT',
                          'PUSH_SEND', 'RETURNBACK'));

COMMENT ON TABLE touch_event IS '触点事件快照（P5-B98 / DESIGN-T2 §3-D4；棒⑥卡5 扩海报四级）：八类 LANDING_VISIT/LANDING_LEAD/POSTER_SHARE/POSTER_SCAN/POSTER_LEAD/POSTER_VISIT/PUSH_SEND/RETURNBACK，仅存不算（归因算法 B69 定案延后）；T2-01 采集监控直读本表';
COMMENT ON COLUMN touch_event.touch_type IS '触点类型八值：LANDING_VISIT 落地页访问 / LANDING_LEAD 落地页留资 / POSTER_SHARE 海报分享 / POSTER_SCAN 海报扫码 / POSTER_LEAD 海报留资 / POSTER_VISIT 海报到店 / PUSH_SEND 推送发送 / RETURNBACK 渠道回传';
