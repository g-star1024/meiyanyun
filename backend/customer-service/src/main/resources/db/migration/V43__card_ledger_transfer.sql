-- B85 卡1：card_ledger.change_type CHECK 扩 TRANSFER（资产转移流水类型）。
-- 资产转移动账权威收口 customer 域（B85 定案 D1）：转出卡一行负额流水、转入卡一行正额流水，
-- 同 bizRef=RP 单号成对落账；本迁移仅放行新枚举值，零存量数据变更。
-- 双库可重入：DROP CONSTRAINT IF EXISTS 先清旧约束再 ADD，正式库与 seed 库各执行一次即幂等。
ALTER TABLE card_ledger DROP CONSTRAINT IF EXISTS chk_card_ledger_change_type;
ALTER TABLE card_ledger ADD CONSTRAINT chk_card_ledger_change_type
    CHECK (change_type IN ('RECHARGE', 'CONSUME', 'REFUND', 'ADJUST', 'TRANSFER'));
