# DESIGN-P5-B76：R03 新客转化漏斗接真

> **批次**：P5-B76（M1 纵深批第七卡，R03-R09 七模板接真第四卡）
> **日期**：2026-09-20
> **主题**：R03 新客转化漏斗接真——422 占位→arrival + consult_plan 真实漏斗数据

---

## §0 调研总述

### 数据源实证

| 表 | 服务 | 关键字段 | 漏斗环节 |
|----|------|---------|---------|
| `arrival` | txn-service | ah_no, store_code, channel(WALK_IN/REFERRAL/MARKETING/APPOINTMENT), arrived_at, status | 到店 |
| `consult_plan` | txn-service | plan_id, store_code, consultant_id, arrival_id, status, plan_amount, created_at, paid_at | 咨询＋成交 |

**漏斗口径**：
- **到店数**：arrival 表按 arrived_at 时间窗口＋store_code＋channel 分组计数
- **咨询数**：consult_plan 表按 created_at 时间窗口＋store_code 分组计数（排除 ABANDONED）
- **成交数**：consult_plan 表 status IN (PAID, TREATING, DONE) 按 created_at 时间窗口＋store_code 计数
- **转化率**：成交数 / 到店数 × 100%

### 代码实证

| 项 | 现状 |
|----|------|
| R03 种子数据 | `ReportDataInitializer` L64：id=R03, name=新客转化漏斗, period=WEEK, dimensions=渠道,门店, metrics=到店数,咨询数,成交数,转化率 |
| ReportDataCollector | switch 含 R01/R02/R05/R07/R09，无 R03 |
| ReportService.SUPPORTED | Set.of("R01","R02","R05","R07","R09")，无 R03 |
| ReportService.resolvePeriod | 仅处理 DAY/MONTH，WEEK 抛 422 |
| ReportService.validatePeriod | 仅校验 DAY/MONTH 格式，WEEK 抛 422 |
| 前端 SUPPORTED_IDS | Set(['R01','R02'])，无 R03 |
| 前端 periodOptions | 仅处理 DAY/MONTH，WEEK 无选项 |
| txn 内部端点 | 无漏斗/到店/咨询统计端点 |
| ArrivalRepository | 4 方法（幂等/序号/排队号/超时），无聚合统计 |
| PlanRepository | 7 方法（按客户/方案单号/状态/门店），无聚合统计 |

---

## §1 八项定案

### 1. 零新表/零 Flyway

完全复用既有 arrival + consult_plan 表，零 DDL。

### 2. 新建 txn 内部端点

`GET /api/txn/internal/funnel-stats?from=yyyy-MM-dd&to=yyyy-MM-dd`

- 权限：`@RequirePerm("internal:finance-flow")`（复用既有权限码）
- 响应：`{ "rows": [{ "storeCode", "channel", "arrivalCount", "consultCount", "dealCount" }] }`
- 到店数：arrival 按 store_code＋channel 分组，arrived_at 在 [from, to) 窗口
- 咨询数：consult_plan 按 store_code 分组，created_at 在 [from, to) 窗口，status != 'ABANDONED'
- 成交数：consult_plan 按 store_code 分组，created_at 在 [from, to) 窗口，status IN ('PAID','TREATING','DONE')
- 不做 DataScope 收敛（由 finance 二次过滤）

### 3. 新建 ArrivalRepository 聚合查询

```java
@Query("SELECT a.storeCode, a.channel, COUNT(a) FROM Arrival a " +
       "WHERE a.arrivedAt >= :from AND a.arrivedAt < :to " +
       "GROUP BY a.storeCode, a.channel")
List<Object[]> funnelArrivals(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
```

### 4. 新建 PlanRepository 聚合查询

```java
@Query("SELECT p.storeCode, COUNT(p) FROM ConsultPlan p " +
       "WHERE p.createdAt >= :from AND p.createdAt < :to AND p.status <> 'ABANDONED' " +
       "GROUP BY p.storeCode")
List<Object[]> funnelConsults(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

@Query("SELECT p.storeCode, COUNT(p) FROM ConsultPlan p " +
       "WHERE p.createdAt >= :from AND p.createdAt < :to " +
       "AND p.status IN ('PAID','TREATING','DONE') " +
       "GROUP BY p.storeCode")
List<Object[]> funnelDeals(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
```

### 5. FinanceAggregationService 新增 fetchFunnelStats

复刻既有 fetchRefundSummary 范式，RestTemplate 调 `/api/txn/internal/funnel-stats`，软降级空集合。

### 6. ReportDataCollector 新增 collectR03

- 调 fetchFunnelStats 取漏斗数据
- 按 (channel, storeCode) 分组
- 叠加 consultCount/dealCount 到对应 storeCode 行
- resolveStoreNames 解析门店名
- DataScope.canReadStore 逐行过滤
- 计算转化率 = dealCount / arrivalCount × 100%
- 表头：渠道, 门店, 到店数, 咨询数, 成交数, 转化率(%)

### 7. WEEK 周期处理

**后端 ReportService**：
- resolvePeriod：WEEK 默认取上一个 ISO 周（`2026-W38` 格式）
- validatePeriod：WEEK 接受 `yyyy-Www` 格式（正则 `^\d{4}-W\d{1,2}$`）
- collectR03 内部将 WEEK 转为 from/to OffsetDateTime 传给 fetchFunnelStats

**前端 M1ReportView.vue**：
- periodOptions 增加 WEEK 分支：近 4 个 ISO 周选项（`2026-W38` 格式，label 为"第38周(9/14-9/20)"）

**前端 m1Report.ts**：
- SUPPORTED_IDS 加 'R03'

### 8. ReportService.SUPPORTED 扩展

`Set.of("R01", "R02", "R03", "R05", "R07", "R09")`

---

## §2 不做项

| 项 | 原因 |
|----|------|
| 新客 vs 全客区分 | arrival 表无首诊标记，visit_count 实时性不足，首卡接真优先 |
| 时段趋势对比 | 首卡接真优先 |
| 渠道归因（线上→到店） | customer.channel 与 arrival.channel 枚举不同，跨域归因远期 |
| 咨询师维度 | 首卡按渠道＋门店，咨询师维度远期 |
| 新内部端点加权限码 | 复用 internal:finance-flow |

---

## §3 施工序

1. txn-service：ArrivalRepository + PlanRepository 各加聚合查询方法
2. txn-service：新建 InternalFunnelController（funnel-stats 端点）
3. finance-service：FinanceAggregationService 新增 fetchFunnelStats
4. finance-service：ReportDataCollector 新增 collectR03 + switch case
5. finance-service：ReportService SUPPORTED 扩展 + resolvePeriod/validatePeriod WEEK 处理
6. frontend：m1Report.ts SUPPORTED_IDS 加 R03
7. frontend：M1ReportView.vue periodOptions 加 WEEK 分支
8. mvn compile + vue-tsc 验证

---

## §4 文件改动预估

| # | 文件 | 改动 |
|---|------|------|
| 1 | txn-service ArrivalRepository.java | +funnelArrivals 聚合查询 |
| 2 | txn-service PlanRepository.java | +funnelConsults +funnelDeals 聚合查询 |
| 3 | txn-service InternalFunnelController.java | **新建**（~60 行） |
| 4 | finance-service FinanceAggregationService.java | +fetchFunnelStats（~30 行） |
| 5 | finance-service ReportDataCollector.java | +collectR03 + switch case（~40 行） |
| 6 | finance-service ReportService.java | SUPPORTED 扩展 + resolvePeriod/validatePeriod WEEK |
| 7 | frontend m1Report.ts | SUPPORTED_IDS 加 R03 |
| 8 | frontend M1ReportView.vue | periodOptions WEEK 分支 |

---

## §5 数字影响

| 指标 | 值 |
|------|-----|
| ✅ | 109（不变） |
| 🔧 | 1（不变） |
| ⬜ | 55（不变） |
| 合计 | 166 |
| 新页面 | 0 |
| 新表/Flyway | 0 |
| 新权限码 | 0 |

---

## §6 风险与降级

| 风险 | 降级策略 |
|------|---------|
| txn-service 漏斗端点不可达 | fetchFunnelStats 软降级空集合，报表空态 |
| arrival/consult_plan 无数据 | 空 rows，报表显示"—" |
| WEEK 格式解析失败 | validatePeriod 抛 422 中文提示 |

---

## §7 卡序

- **卡0**：本定案（DESIGN 文档）
- **卡1**：施工（txn 端点＋finance 取数＋前端 WEEK 支持）
- **卡2**：批末落账（DELIVERY＋台账四册）
