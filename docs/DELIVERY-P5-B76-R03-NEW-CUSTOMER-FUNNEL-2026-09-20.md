# DELIVERY-P5-B76：R03 新客转化漏斗接真

> **批次**：P5-B76（M1 纵深批第七卡，R03-R09 七模板接真第四卡）
> **日期**：2026-09-20
> **主题**：R03 新客转化漏斗接真——arrival+consult_plan 聚合查询＋funnel-stats 内部端点＋collectR03＋WEEK 周期支持
> **commit**：feat `481bd0d`（8 files +218/-3）
> **数字**：✅109/🔧1/⬜55=166 不变

---

## §1 现状与缺口

| 项 | 接真前 | 接真后 |
|----|--------|--------|
| R03 模板 | 种子数据已注册，preview/generate 返回 422 | SUPPORTED 含 R03，preview/generate 正常返回真实数据 |
| 数据源 | — | `/api/txn/internal/funnel-stats`（新建端点）|
| 漏斗取数 | — | ArrivalRepository funnelArrivals + PlanRepository funnelConsults/funnelDeals 聚合查询 |
| 周期支持 | 仅 DAY/MONTH | 新增 WEEK（ISO 周 yyyy-Www） |
| 前端 | 报表中心通用管线 | periodOptions 新增 WEEK 分支（近 4 个 ISO 周） |

---

## §2 口径定义

| 项 | 说明 |
|----|------|
| 到店数 | `arrival` 表按 arrived_at 窗口＋store_code＋channel 分组计数 |
| 咨询数 | `consult_plan` 表按 created_at 窗口＋store_code，status≠ABANDONED |
| 成交数 | `consult_plan` 表 status IN ('PAID','TREATING','DONE') |
| 渠道映射 | WALK_IN→自然到店、REFERRAL→转介绍、MARKETING→营销渠道、APPOINTMENT→预约到店 |
| 转化率 | 成交数 / 到店数 × 100% |
| 权限收敛 | `DataScope.canReadStore` 逐行过滤 |
| 门店名解析 | `resolveStoreNames(storeCodes)` → store-service 既有路径 |
| WEEK 默认期段 | 上周（ISO WeekFields，周一为起点） |
| WEEK 前端选项 | 近 4 个 ISO 周（含周区间标签） |

---

## §3 不动项

| 项 | 说明 |
|----|------|
| R01/R02/R05/R07/R09 收集逻辑 | 零改动 |
| 三种格式 Builder | CSV/XLSX/PDF 零改动 |
| 数据库 | 零新表/零 Flyway |
| 权限码 | 零新增（复用 internal:finance-flow） |
| 网关 | 零改动 |

---

## §4 不做项

| 项 | 原因 |
|----|------|
| R04/R06/R08 接真 | 后续 B77-B79 逐卡 |
| 渠道维度下钻（按来源细分） | 首卡接真优先 |
| 漏斗趋势图（多周对比） | 属前端增强，远期 |
| 到店-咨询-成交全链路追踪 | 需 arrival_id 关联，当前聚合口径已满足报表需求 |

---

## §5 施工清单

| # | 文件 | 改动 |
|---|------|------|
| 1 | `txn-service/.../ArrivalRepository.java` | +funnelArrivals @Query（按 storeCode+channel 分组计数）（+5 行） |
| 2 | `txn-service/.../PlanRepository.java` | +funnelConsults + funnelDeals @Query + OffsetDateTime import（+12 行） |
| 3 | `txn-service/.../InternalFunnelController.java` | **新建**，funnel-stats 端点（~68 行） |
| 4 | `finance-service/.../FinanceAggregationService.java` | +fetchFunnelStats 跨域取数（~26 行） |
| 5 | `finance-service/.../ReportDataCollector.java` | +case "R03" +collectR03 方法（~61 行） |
| 6 | `finance-service/.../ReportService.java` | SUPPORTED 加 "R03" +resolvePeriod WEEK +validatePeriod WEEK（~18 行） |
| 7 | `frontend/src/stores/m1Report.ts` | SUPPORTED_IDS 加 'R03'（+1/-1） |
| 8 | `frontend/src/views/M1ReportView.vue` | periodOptions WEEK 分支（+18 行） |

合计：**8 files +218/-3**

---

## §6 三轨真验计划

### curl 轨
```bash
curl -H "X-Auth-Staff: S001" -H "X-Auth-Role: FINANCE" \
  "http://127.0.0.1:18443/api/finance/reports/preview?id=R03&period=2026-W38"
```
预期：返回 headers=[渠道, 门店, 到店数, 咨询数, 成交数, 转化率(%)] + rows（按门店×渠道分组）

### PG 轨
```sql
SELECT store_code, channel, COUNT(*) FROM arrival
WHERE arrived_at >= '2026-09-14' AND arrived_at < '2026-09-21'
GROUP BY store_code, channel;
```

### Chrome 轨
报表中心 → R03 新客转化漏斗 → 选择周次 → 预览 → 真实数据表格

---

## §7 数字影响

| 指标 | 值 |
|------|-----|
| ✅ | 109（不变） |
| 🔧 | 1（不变） |
| ⬜ | 55（不变） |
| 合计 | 166 |
| 新页面 | 0 |
| 新表/Flyway | 0 |
| 新权限码 | 0 |
