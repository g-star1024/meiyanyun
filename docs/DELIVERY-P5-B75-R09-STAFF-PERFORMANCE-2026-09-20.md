# DELIVERY-P5-B75：R09 员工业绩排行接真

> **批次**：P5-B75（M1 纵深批第六卡，R03-R09 七模板接真第三卡）
> **日期**：2026-09-20
> **主题**：R09 员工业绩排行接真——commission-base 跨域取数＋员工名解析＋CommissionRecord 提成叠加
> **commit**：feat `4bf2aef`（3 files +98/-2）
> **数字**：✅109/🔧1/⬜55=166 不变

---

## §1 现状与缺口

| 项 | 接真前 | 接真后 |
|----|--------|--------|
| R09 模板 | 种子数据已注册，preview/generate 返回 422 | SUPPORTED 含 R09，preview/generate 正常返回真实数据 |
| 数据源 | — | `/api/txn/internal/commission-base`（既有端点）+ CommissionRecord 提成叠加 |
| 员工名解析 | — | `/api/org/staff/name-map`（新增 orgBaseUrl 跨域取数） |
| 前端 | 报表中心通用管线，零改动 | 同 |

---

## §2 口径定义

| 项 | 说明 |
|----|------|
| 业绩基数 | `fetchCommissionBase(month)` → txn 按 staffId 聚合 writeoffAmount/writeoffCount/orderAmount/orderCount/refundAmount/refundCount |
| 提成叠加 | `CommissionRecordRepository.findByPeriodOrderByCommissionDesc(period)` 按 staffId 叠加 commission 字段 |
| 员工名解析 | `resolveStaffNames(staffIds)` → org-service `/api/org/staff/name-map?ids=...`，失败回落员工号 |
| 门店名解析 | `resolveStoreNames(storeCodes)` → store-service 既有路径 |
| 排序 | writeoffAmount 降序（业绩高的排前面） |
| 满意度 | 无数据源，诚实置"—" |
| 权限收敛 | `DataScope.canReadStore` 逐行过滤 |

---

## §3 不动项

| 项 | 说明 |
|----|------|
| R01/R02/R05/R07 收集逻辑 | 零改动 |
| 三种格式 Builder | CSV/XLSX/PDF 零改动 |
| 前端 | 零改动 |
| `/api/txn/internal/commission-base` 端点 | 零改动（既有） |
| 数据库 | 零新表/零 Flyway |
| 权限码 | 零新增 |

---

## §4 不做项

| 项 | 原因 |
|----|------|
| R03/R04/R06/R08 接真 | 后续 B76-B79 逐卡 |
| 满意度数据源 | 无既存表/端点，远期 |
| 多维度排行（按区域/按项目） | 首卡接真优先 |
| 提成规则明细展示 | 属提成管理域，非报表域 |

---

## §5 施工清单

| # | 文件 | 改动 |
|---|------|------|
| 1 | `finance-service/.../FinanceAggregationService.java` | +orgBaseUrl @Value +CommissionBaseRow record +fetchCommissionBase +resolveStaffNames（~60 行） |
| 2 | `finance-service/.../ReportDataCollector.java` | +commissionRepo 注入 +collectR09 方法 + switch case "R09"（~36 行） |
| 3 | `finance-service/.../ReportService.java` | SUPPORTED 扩展含 "R09"（+1/-1） |

合计：**3 files +98/-2**

---

## §6 三轨真验计划

### curl 轨
```bash
curl -H "X-Auth-Staff: S001" -H "X-Auth-Role: FINANCE" \
  "http://127.0.0.1:18443/api/finance/reports/preview?id=R09&period=2026-09"
```
预期：返回 headers=[门店, 员工, 业绩(元), 服务人次, 提成(元), 满意度(%)] + rows（按业绩降序）

### PG 轨
```sql
SELECT staff_id, staff_name, SUM(commission) FROM commission_record
WHERE period = '2026-09-01' GROUP BY staff_id, staff_name ORDER BY SUM(commission) DESC;
```

### Chrome 轨
报表中心 → R09 员工业绩排行 → 预览 → 真实数据表格

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
