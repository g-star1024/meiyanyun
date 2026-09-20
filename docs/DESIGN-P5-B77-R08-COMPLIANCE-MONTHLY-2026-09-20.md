# DESIGN-P5-B77：R08 合规检查月报接真

> **批次**：P5-B77（M1 纵深批第八卡，R03-R09 七模板接真第五卡）
> **日期**：2026-09-20
> **主题**：R08 合规检查月报接真——422 占位→compliance_check 真实合规数据

---

## §0 调研总述

### 数据源实证

| 表 | 服务 | 关键字段 | 说明 |
|----|------|---------|------|
| `compliance_check` | audit-service（port 8084） | id, category(六类), title, store_name, status(四态), last_check_at, checker, due_date, created_at, updated_at | B49 卡9 合规中心落地表 |

**六类检查项**（category CHECK 约束）：

| 枚举值 | 中文 |
|--------|------|
| QUALIFICATION | 资质证照 |
| CONSENT | 知情同意 |
| DRUG_TRACE | 药品溯源 |
| PRIVACY | 隐私合规 |
| AD | 医疗广告 |
| INFECTION | 院感管理 |

**四态**（status CHECK 约束）：PASS（合规）、WARN（预警）、FAIL（不合规）、PENDING（待检）

**整改率口径**：compliance_check 无显式整改字段，但复检（ComplianceService.recheck）会更新 status + updated_at。采用启发式口径：
- **已整改数**：status = 'PASS' 且 updated_at > created_at（表明经过复检从非 PASS 转为 PASS）
- **整改率**：已整改数 / (FAIL + WARN 当前数 + 已整改数) × 100%
- 即：当前 PASS 且有修改痕迹的 / 总问题基数

### 代码实证

| 项 | 现状 |
|----|------|
| R08 种子数据 | `ReportDataInitializer` L74：id=R08, name=合规检查月报, period=MONTH, dimensions=门店,合规项, metrics=通过率,问题数,整改率, subscribed=true |
| ReportDataCollector | switch 含 R01/R02/R03/R05/R07/R09，无 R08 |
| ReportService.SUPPORTED | Set.of("R01","R02","R03","R05","R07","R09")，无 R08 |
| 前端 SUPPORTED_IDS | Set(['R01','R02','R03'])，无 R08 |
| audit-service 内部端点 | 无任何 /api/audit/internal/* 端点 |
| ComplianceCheckRepository | JpaRepository + JpaSpecificationExecutor，无聚合查询方法 |
| ComplianceService | list(category, status) + recheck()，无统计方法 |
| FinanceAggregationService | 无 audit.service.url 配置，无合规取数方法 |

---

## §1 八项定案

### 1. 零新表/零 Flyway

完全复用既有 compliance_check 表，零 DDL。

### 2. 新建 audit 内部端点

`GET /api/audit/internal/compliance-stats?from=yyyy-MM-dd&to=yyyy-MM-dd`

- 权限：`@RequirePerm("internal:finance-flow")`（复用既有权限码，与 txn 内部端点一致）
- 响应：`{ "rows": [{ "storeName", "category", "totalCount", "passCount", "warnCount", "failCount", "pendingCount", "remediatedCount" }] }`
- 按 store_name + category 分组
- last_check_at 在 [from, to) 窗口内
- remediatedCount：status='PASS' AND updated_at > created_at 的计数
- 不做 DataScope 收敛（compliance_check 用 store_name 文本，非 store_code，由 audit 域全量返回）

### 3. 新建 ComplianceCheckRepository 聚合查询

```java
@Query("SELECT c.storeName, c.category, COUNT(c), " +
       "SUM(CASE WHEN c.status = 'PASS' THEN 1 ELSE 0 END), " +
       "SUM(CASE WHEN c.status = 'WARN' THEN 1 ELSE 0 END), " +
       "SUM(CASE WHEN c.status = 'FAIL' THEN 1 ELSE 0 END), " +
       "SUM(CASE WHEN c.status = 'PENDING' THEN 1 ELSE 0 END), " +
       "SUM(CASE WHEN c.status = 'PASS' AND c.updatedAt > c.createdAt THEN 1 ELSE 0 END) " +
       "FROM ComplianceCheck c " +
       "WHERE c.lastCheckAt >= :from AND c.lastCheckAt < :to " +
       "GROUP BY c.storeName, c.category")
List<Object[]> complianceStats(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
```

### 4. 新建 InternalComplianceController

audit-service 新建 `InternalComplianceController.java`（~40 行），复刻 txn InternalFunnelController 范式：
- `@RestController @RequestMapping("/api/audit/internal")`
- `@RequirePerm("internal:finance-flow")`
- 调用 complianceStats 聚合查询，组装 rows 返回

### 5. FinanceAggregationService 新增 fetchComplianceStats

- 新增 `@Value("${audit.service.url:http://127.0.0.1:8084}")` auditBaseUrl
- 新增 `fetchComplianceStats(String from, String to)` 方法
- RestTemplate 调 `/api/audit/internal/compliance-stats`，软降级空集合

### 6. ReportDataCollector 新增 collectR08

- 将 MONTH 期段（如 `2026-08`）转为 from/to（月初→下月初）
- 调 fetchComplianceStats 取合规数据
- 六类 category 中文映射
- 表头：门店, 合规项, 检查总数, 通过数, 通过率(%), 问题数, 整改率(%)
- 通过率 = passCount / totalCount × 100%
- 问题数 = warnCount + failCount
- 整改率 = remediatedCount / (remediatedCount + warnCount + failCount) × 100%，分母为零时 "—"

### 7. ReportService.SUPPORTED 扩展

`Set.of("R01", "R02", "R03", "R05", "R07", "R08", "R09")`

R08 period=MONTH，resolvePeriod/validatePeriod 已有 MONTH 处理，无需改动。

### 8. 前端 m1Report.ts SUPPORTED_IDS 扩展

`SUPPORTED_IDS = new Set(['R01', 'R02', 'R03', 'R08'])`

R08 是 MONTH 周期，M1ReportView.vue 已有 MONTH periodOptions 分支，无需改动。

---

## §2 不做项

| 项 | 原因 |
|----|------|
| 合规趋势图（月度对比） | 首卡接真优先 |
| 合规项下钻到具体检查记录 | 报表层仅聚合，明细走合规中心 /m1-compliance |
| DataScope 门店域收敛 | compliance_check 用 store_name 文本非 store_code，首卡全量返回 |
| 新权限码 | 复用 internal:finance-flow |
| 审计日志复检关联 | 首卡用 updated_at > created_at 启发式，精确审计关联远期 |

---

## §3 施工序

1. audit-service：ComplianceCheckRepository 加 complianceStats 聚合查询
2. audit-service：新建 InternalComplianceController（compliance-stats 端点）
3. finance-service：FinanceAggregationService 新增 auditBaseUrl 配置 + fetchComplianceStats
4. finance-service：ReportDataCollector 新增 collectR08 + switch case
5. finance-service：ReportService SUPPORTED 扩展含 R08
6. frontend：m1Report.ts SUPPORTED_IDS 加 R08
7. mvn compile 验证

---

## §4 文件改动预估

| # | 文件 | 改动 |
|---|------|------|
| 1 | audit-service ComplianceCheckRepository.java | +complianceStats 聚合查询 |
| 2 | audit-service InternalComplianceController.java | **新建**（~40 行） |
| 3 | finance-service FinanceAggregationService.java | +auditBaseUrl +fetchComplianceStats（~30 行） |
| 4 | finance-service ReportDataCollector.java | +collectR08 + switch case（~40 行） |
| 5 | finance-service ReportService.java | SUPPORTED 扩展含 R08 |
| 6 | frontend m1Report.ts | SUPPORTED_IDS 加 R08 |

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
| audit-service 合规端点不可达 | fetchComplianceStats 软降级空集合，报表空态 |
| compliance_check 无当月数据 | 空 rows，报表显示空态 |
| 整改率启发式不精确 | 首卡可接受，精确审计关联远期 |

---

## §7 卡序

- **卡0**：本定案（DESIGN 文档）
- **卡1**：施工（audit 端点＋finance 取数＋前端 SUPPORTED 扩展）
- **卡2**：批末落账（DELIVERY＋台账四册）
