# DESIGN-P5-B74：R05 项目疗程消耗报表接真

> **卡号**：P5-B74（M1 纵深批第五卡，R03-R09 七模板接真第二卡）
> **日期**：2026-09-20
> **状态**：定案待施工

---

## §1 现状与缺口

### 1.1 报表中心当前能力（B73 闭合后）

| 项 | 现状 |
|----|------|
| 已支持 | R01 门店营收日报（B71）、R02 月度经营分析（B71+B72 区域维度）、R07 退款与纠纷台账（B73） |
| 未支持 | R03/R04/R05/R06/R08/R09 六模板 422 |
| 架构 | ReportDataCollector switch + ReportService.SUPPORTED = {R01, R02, R07} |

### 1.2 R05 模板需求（种子数据定义）

| 属性 | 值 |
|------|-----|
| 名称 | 项目疗程消耗报表 |
| 分类 | OPERATION |
| 周期 | MONTH |
| 维度 | 门店、项目品类 |
| 指标 | 剩余次数、核销率、到期数 |
| 来源 | `ReportDataInitializer` L68-69 |

### 1.3 数据源分析

| 数据需求 | 来源实体 | 现有取数路径 |
|----------|----------|-------------|
| 疗程卡总次数/剩余次数 | `MemberCard`（customer-service）`cardType=COURSE` | `fetchCards(null)` 已返回 totalTimes/remainTimes/cardType/expiresAt/status/cardItem/storeCode |
| 即将到期卡数 | `MemberCard.expiresAt` 30 天内且 status=在用 | 同上 |
| 核销率 | (totalTimes - remainTimes) / totalTimes | 同上，Java 层计算 |

**关键发现**：R05 三大指标可完全基于 `fetchCards()` 现有取数路径在 Java 层聚合，**零新内部端点**。

---

## §2 口径定义

### 2.1 collectR05 数据收集

```
输入：month（yyyy-MM，当前未用于过滤——MemberCard 为快照态，反映当前剩余/到期）
流程：
  1. aggregation.fetchCards(null) → 全量卡列表
  2. 过滤 cardType == "COURSE"
  3. GROUP BY (storeCode, cardItem)
  4. 每组聚合：
     - totalTimes = SUM(totalTimes)
     - remainTimes = SUM(remainTimes)
     - consumedTimes = totalTimes - remainTimes
     - writeoffRate = consumedTimes * 100 / totalTimes（totalTimes=0 时 "—"）
     - expiringCount = COUNT(expiresAt 在 30 天内 AND status=在用)
  5. resolveStoreNames 中文门店名
输出：headers = [门店, 项目/卡项, 总次数, 已消耗, 剩余次数, 核销率(%), 即将到期(张)]
```

### 2.2 ReportService SUPPORTED 扩展

```java
// 之前
static final Set<String> SUPPORTED = Set.of("R01", "R02", "R07");
// 之后
static final Set<String> SUPPORTED = Set.of("R01", "R02", "R05", "R07");
```

### 2.3 ReportDataCollector switch 扩展

```java
case "R05" -> collectR05(period);
```

---

## §3 不动项

| 项 | 说明 |
|----|------|
| R01/R02/R07 收集逻辑 | 零改动 |
| 三种格式 Builder | CSV/XLSX/PDF Builder 零改动 |
| 前端 | 零改动（报表中心已有通用 preview/generate/download 管线） |
| fetchCards 取数路径 | 零改动（复用现有 customer-service internal/card-balances 端点） |
| 数据库 | 零新表/零 Flyway |
| 权限 | 零新权限码 |
| 网关 | 零改动 |
| 金额 | 不涉及金额（次数/百分比/张数） |

---

## §4 不做项

| 项 | 原因 |
|----|------|
| R03/R04/R06/R08/R09 接真 | 后续 B75-B79 逐卡处理 |
| 项目品类跨域解析（CatalogProduct.categoryId） | 需 store-service 额外端点，cardItem 已足够区分 |
| 月度趋势对比 | 首卡接真优先，趋势图后续 |
| 储值卡（CARD）消耗统计 | R05 聚焦疗程卡（COURSE），储值卡属 R01/R02 范畴 |

---

## §5 施工清单

| # | 文件 | 改动 |
|---|------|------|
| 1 | `finance-service/.../ReportDataCollector.java` | +collectR05 方法 + switch case "R05" |
| 2 | `finance-service/.../ReportService.java` | SUPPORTED 扩展含 "R05" |

预估：**2 files, ~+40 行**

---

## §6 三轨真验计划

### curl 轨
```bash
curl -H "X-Auth-Staff: S001" -H "X-Auth-Role: FINANCE" \
  "http://127.0.0.1:18443/api/finance/reports/preview?id=R05&period=2026-09"
```
预期：返回 headers + rows（门店×卡项维度），不再 422

### PG 轨
```sql
SELECT card_type, COUNT(*), SUM(total_times), SUM(remain_times)
FROM member_card WHERE card_type = 'COURSE'
GROUP BY card_type;
```

### Chrome 轨
报表中心 → R05 项目疗程消耗报表 → 预览 → 应显示真实数据表格

---

## §7 数字影响

| 指标 | 值 |
|------|-----|
| ✅ | 109（不变） |
| 🔧 | 1（不变） |
| ⬜ | 55（不变，R05 从 422 收窄→真实但记账数字不动） |
| 合计 | 166 |
| 新页面 | 0 |
| 新表/Flyway | 0 |
| 新权限码 | 0 |
