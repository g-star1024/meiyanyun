# DELIVERY-P5-B74：R05 项目疗程消耗报表接真

> **批次**：P5-B74（M1 纵深批第五卡，R03-R09 七模板接真第二卡）
> **日期**：2026-09-20
> **主题**：R05 项目疗程消耗报表接真——422 占位→MemberCard COURSE 真实数据，按门店×项目/卡项聚合
> **commit**：feat `d9c08d1`（2 files +50/-1）
> **数字**：✅109/🔧1/⬜55=166 不变

---

## §1 现状与缺口

| 项 | 接真前 | 接真后 |
|----|--------|--------|
| R05 模板 | 种子数据已注册，preview/generate 返回 422 | SUPPORTED 含 R05，preview/generate 正常返回真实数据 |
| 数据源 | — | `fetchCards(null)` 现有取数路径，零新内部端点 |
| 前端 | 报表中心通用管线，零改动 | 同 |

---

## §2 口径定义

| 项 | 说明 |
|----|------|
| 数据源 | `FinanceAggregationService.fetchCards(null)` → 全量 MemberCard |
| 过滤 | `cardType == "COURSE"`（仅疗程卡，储值卡不在此报表） |
| 分组键 | `(storeCode, cardItem)` |
| 聚合指标 | totalTimes(SUM)、remainTimes(SUM)、consumedTimes(差)、writeoffRate(%)、expiringCount(30天内到期且在用) |
| 权限收敛 | `DataScope.canReadStore` 逐行过滤 |

---

## §3 不动项

| 项 | 说明 |
|----|------|
| R01/R02/R07 收集逻辑 | 零改动 |
| 三种格式 Builder | CSV/XLSX/PDF 零改动 |
| 前端 | 零改动 |
| fetchCards 取数路径 | 零改动 |
| 数据库 | 零新表/零 Flyway |
| 权限码 | 零新增 |

---

## §4 不做项

| 项 | 原因 |
|----|------|
| R03/R04/R06/R08/R09 接真 | 后续 B75-B79 逐卡 |
| CatalogProduct 品类跨域解析 | cardItem 已足够区分，避免额外端点 |
| 月度趋势对比 | 首卡接真优先 |
| 储值卡消耗统计 | 属 R01/R02 范畴 |

---

## §5 施工清单

| # | 文件 | 改动 |
|---|------|------|
| 1 | `finance-service/.../ReportDataCollector.java` | +collectR05 方法（~46 行）+ switch case "R05" + 2 imports |
| 2 | `finance-service/.../ReportService.java` | SUPPORTED 扩展含 "R05"（+1/-1） |

合计：**2 files +50/-1**

---

## §6 三轨真验计划

### curl 轨
```bash
curl -H "X-Auth-Staff: S001" -H "X-Auth-Role: FINANCE" \
  "http://127.0.0.1:18443/api/finance/reports/preview?id=R05&period=2026-09"
```
预期：返回 headers=[门店, 项目/卡项, 总次数, 已消耗, 剩余次数, 核销率(%), 即将到期(张)] + rows

### PG 轨
```sql
SELECT card_type, COUNT(*), SUM(total_times), SUM(remain_times)
FROM member_card WHERE card_type = 'COURSE' GROUP BY card_type;
```

### Chrome 轨
报表中心 → R05 项目疗程消耗报表 → 预览 → 真实数据表格

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
