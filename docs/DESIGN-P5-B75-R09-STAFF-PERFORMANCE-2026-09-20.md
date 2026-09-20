# DESIGN-P5-B75：R09 员工业绩排行报表接真

> **卡号**：P5-B75（M1 纵深批第五卡，R03-R09 七模板接真第三卡）
> **日期**：2026-09-20
> **状态**：定案待施工

---

## §1 现状与缺口

### 1.1 报表中心当前能力（B74 闭合后）

| 项 | 现状 |
|----|------|
| 已支持 | R01 门店营收日报（B71）、R02 月度经营分析（B71+B72）、R05 项目疗程消耗（B74）、R07 退款与纠纷台账（B73） |
| 未支持 | R03/R04/R06/R08/R09 五模板 |
| 架构 | ReportDataCollector switch + ReportService.SUPPORTED = {R01, R02, R05, R07} |

### 1.2 R09 模板需求（种子数据定义）

| 属性 | 值 |
|------|-----|
| 名称 | 员工业绩排行 |
| 分类 | STAFF |
| 周期 | MONTH |
| 维度 | 门店、员工 |
| 指标 | 业绩、服务人次、满意度、提成 |
| 来源 | `ReportDataInitializer` L76-77 |

### 1.3 数据源分析

| 数据需求 | 来源 | 现有取数路径 |
|----------|------|-------------|
| 业绩（划扣金额） | txn-service `commission-base` 端点 | `/api/txn/internal/commission-base?month=YYYY-MM` → rows[{staffId, storeCode, writeoffAmount, writeoffCount, orderAmount, orderCount, refundAmount, refundCount}] |
| 提成额 | 本地 `CommissionRecord` 实体 | `CommissionRecordRepository.findByPeriodOrderByCommissionDesc(period)` → staffId/commission(分)/status |
| 员工名 | org-service | `/api/org/staff/name-map?ids=SE006,SE007` → `{"SE006":"沈咨询"}` |
| 门店名 | store-service | `resolveStoreNames()` 已有 |
| 满意度 | **无数据源** | 代码库中无满意度相关实现，诚实置"—" |

**关键发现**：
- 业绩数据复用既有 `/api/txn/internal/commission-base`（CommissionService.fetchCommissionBase 已调此端点但为 private）
- 提成数据来自本地 CommissionRecord（已有 Repository 方法）
- 员工名解析需新增 org-service 调用（FinanceAggregationService 当前无 orgBaseUrl）
- **满意度无源**——代码库中无评价/满意度模块，表头保留但值诚实置"—"

---

## §2 口径定义

### 2.1 collectR09 数据收集

```
输入：month（yyyy-MM）
流程：
  1. aggregation.fetchCommissionBase(month) → Map<staffId, {storeCode, writeoffAmount, writeoffCount, ...}>
  2. commissionRepo.findByPeriodOrderByCommissionDesc(period) → Map<staffId, CommissionRecord>
  3. 合并：以 commission-base 为主键（覆盖所有有业绩的员工），叠加提成
  4. DataScope.canReadStore(storeCode) 过滤
  5. resolveStaffNames 解析员工名
  6. resolveStoreNames 解析门店名
  7. 按 writeoffAmount 降序排序
输出：headers = [门店, 员工, 业绩(元), 服务人次, 提成(元), 满意度(%)]
```

### 2.2 口径说明

| 列名 | 口径 | 来源 |
|------|------|------|
| 门店 | storeCode → 门店名 | commission-base + resolveStoreNames |
| 员工 | staffId → 员工名 | commission-base + resolveStaffNames |
| 业绩(元) | writeoffAmount（划扣确认收入，分→元） | commission-base |
| 服务人次 | writeoffCount（划扣笔数） | commission-base |
| 提成(元) | CommissionRecord.commission（分→元），无记录置"—" | 本地 commission_record 表 |
| 满意度(%) | 无数据源，置"—" | N/A |

### 2.3 FinanceAggregationService 新增

```java
@Value("${org.service.url:http://127.0.0.1:8081}")
private String orgBaseUrl;

public Map<String, String> resolveStaffNames(List<String> staffIds) {
    // 复刻 resolveStoreNames 范式，调 /api/org/staff/name-map
}

public Map<String, CommissionBaseRow> fetchCommissionBase(String month) {
    // 复刻 CommissionService.fetchCommissionBase 逻辑
    // 调 /api/txn/internal/commission-base?month=YYYY-MM
    // 返回 Map<staffId, CommissionBaseRow(storeCode, writeoffAmount, writeoffCount, ...)>
}
```

### 2.4 ReportService SUPPORTED 扩展

```java
static final Set<String> SUPPORTED = Set.of("R01", "R02", "R05", "R07", "R09");
```

### 2.5 ReportDataCollector switch 扩展

```java
case "R09" -> collectR09(period);
```

---

## §3 不动项

| 项 | 说明 |
|----|------|
| R01/R02/R05/R07 收集逻辑 | 零改动 |
| 三种格式 Builder | 零改动 |
| 前端 | 零改动（通用 preview/generate/download 管线） |
| commission-base 端点 | 零改动（既有 txn-service 端点） |
| CommissionRecord 实体/表 | 零改动 |
| 数据库 | 零新表/零 Flyway |
| 权限 | 零新权限码 |
| 网关 | 零改动 |
| 金额 | bigint 存分，展示时÷100 转元（沿用既有 fen() helper） |

---

## §4 不做项

| 项 | 原因 |
|----|------|
| 满意度数据接入 | 代码库无评价/满意度模块，后续独立专项 |
| R03/R04/R06/R08 接真 | 后续 B76-B79 逐卡处理 |
| 提成计算/试算 | R09 仅展示已有 CommissionRecord，不新增计算逻辑 |
| 月度趋势/环比 | 首卡接真优先，趋势后续 |
| 按角色/职级筛选 | 首卡接真优先 |

---

## §5 施工清单

| # | 文件 | 改动 |
|---|------|------|
| 1 | `finance-service/.../FinanceAggregationService.java` | +orgBaseUrl +resolveStaffNames +fetchCommissionBase +CommissionBaseRow record |
| 2 | `finance-service/.../ReportDataCollector.java` | +CommissionRecordRepository 注入 +collectR09 +switch case "R09" |
| 3 | `finance-service/.../ReportService.java` | SUPPORTED 扩展含 "R09" |

预估：**3 files, ~+80 行**

---

## §6 三轨真验计划

### curl 轨
```bash
curl -H "X-Auth-Staff: S001" -H "X-Auth-Role: FINANCE" \
  "http://127.0.0.1:18443/api/finance/reports/preview?id=R09&period=2026-09"
```
预期：返回 headers + rows（门店×员工维度，按业绩降序），不再 422

### PG 轨
```sql
SELECT staff_id, store_code, commission, base_amount, order_count
FROM commission_record WHERE period = '2026-09-01'
ORDER BY commission DESC;
```

### Chrome 轨
报表中心 → R09 员工业绩排行 → 预览 → 应显示真实数据表格

---

## §7 数字影响

| 指标 | 值 |
|------|-----|
| ✅ | 109（不变） |
| 🔧 | 1（不变） |
| ⬜ | 55（不变，R09 从 422 收窄→真实但记账数字不动） |
| 合计 | 166 |
| 新页面 | 0 |
| 新表/Flyway | 0 |
| 新权限码 | 0 |
