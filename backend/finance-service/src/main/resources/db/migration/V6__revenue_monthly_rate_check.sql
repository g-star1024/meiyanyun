-- V6__revenue_monthly_rate_check.sql
-- 美研云门店中台 - B5 成本库存：revenue_monthly 两率约束放宽（零收入月份豁免）
-- 创建时间: 2026-09-05
-- 数据库: PostgreSQL 15+
--
-- 背景：
--   B5 耗材双签终审后成本经内部端点落 fund_entry + cost_allocation，并触发
--   refreshRevenueMonthly 重算门店月报。新开店/当月无营收门店会出现
--   revenue=0、cost>0、gross_profit=-cost 的月份：此时成本率/毛利率
--   数学上无定义（除以 0），服务层与前端口径一致地将两率记 0.000
--   （FundEntryService：revenue<=0 → 0.000；finReports.ts：revenue ? ratio : 0）。
--   但 M6 镜像期预置的 chk_rate_sum 要求 abs(cost_rate+gross_rate-1)<0.001，
--   零收入月 0+0 ≠ 1，INSERT 被拒导致成本分录同事务回滚（23514）。
--
-- 处理：
--   chk_rev_balance（revenue = cost + gross_profit）是资金恒等式，所有月份都必须成立，保留；
--   chk_rate_sum 仅在收入为正时校验两率互补（率值才有数学意义），收入 ≤ 0 的月份豁免。
--   约束 DROP IF EXISTS + ADD 可重入；JPA ddl-auto=update 不维护 CHECK，由本迁移统一管理。

ALTER TABLE revenue_monthly DROP CONSTRAINT IF EXISTS chk_rate_sum;
ALTER TABLE revenue_monthly ADD CONSTRAINT chk_rate_sum
    CHECK (revenue <= 0 OR abs(cost_rate + gross_rate - 1.000) < 0.001);
