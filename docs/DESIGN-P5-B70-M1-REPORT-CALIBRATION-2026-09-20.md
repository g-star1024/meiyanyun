# DESIGN-P5-B70 M1 报表口径微调批

> **批次**：P5-B70 M1 报表口径微调批
> **类型**：B 类 bug 修复＋台账勾销
> **创建**：2026-09-20
> **状态**：卡0 定案完成，待卡1 施工

---

## §0 调研总述

M1 集团管控 17 条目盘点（04-backlog L128-L151）：
- **8 ✅**：L129/L131/L134/L135/L136/L142/L144/L145/L146/L147/L148/L149/L150/L151（B49-B54 各批已闭合）
- **2 ⬜ 待勾销**：L132（aggregatedRevenue 读时聚合）/L133（REJECTED reset DRAFT）——B69 卡1 已施工（feat `363bb1d`＋fix `832a1fa`），但 04-backlog 未更新勾销标记
- **7 远期**：L137（R03-R09 七模板）/L138（XLSX·PDF 导出）/L139（R02 维度扩展）/L140（mapPayMethod transfer）/L141（R02 毛利率%格式化）/L143（URGENT 加急标记源）/L130（ai-service Flyway 悬置）

本批收窄为 **L132/L133 勾销＋L140/L141 两个真实 bug 修复**，其余远期项不动。

---

## §1 口径定义

### 1.1 BUG-1：mapPayMethod transfer 映射缺失

**现状**：`mapPayMethod` 方法在三处独立副本中均不映射 `"transfer"`：
- [FinanceAggregationService.java](file:///Users/huluobo/WorkBuddy/2026-08-15-23-51-02/meiyun-platform/backend/finance-service/src/main/java/com/meiyun/finance/FinanceAggregationService.java) L534-540
- [TripartiteReconcileService.java](file:///Users/huluobo/WorkBuddy/2026-08-15-23-51-02/meiyun-platform/backend/finance-service/src/main/java/com/meiyun/finance/TripartiteReconcileService.java) L307-313
- [FinanceInternalOpsService.java](file:///Users/huluobo/WorkBuddy/2026-08-15-23-51-02/meiyun-platform/backend/finance-service/src/main/java/com/meiyun/finance/FinanceInternalOpsService.java) L271-277

```java
private String mapPayMethod(String payMethod) {
    if (payMethod == null) return null;
    return switch (payMethod) {
        case "cash", "card", "wxpay", "alipay", "balance" -> payMethod;
        default -> null;  // ← "transfer" 落此分支，返回 null
    };
}
```

**影响**：银行转账（`transfer`）在 R01 收入明细报表中被归为"其他"（null→未标记渠道），而 [ReportCsvBuilder.java](file:///Users/huluobo/WorkBuddy/2026-08-15-23-51-02/meiyun-platform/backend/finance-service/src/main/java/com/meiyun/finance/ReportCsvBuilder.java) L47-53 的 `CHANNEL_CN` 映射表已有 `"transfer" -> "银行转账"` 但因上游不返回 transfer 永远不会命中。

**修复**：三处副本统一加 `"transfer" -> "transfer"` 分支。

### 1.2 BUG-2：R02 毛利率%格式化不匹配

**现状**：[ReportCsvBuilder.java](file:///Users/huluobo/WorkBuddy/2026-08-15-23-51-02/meiyun-platform/backend/finance-service/src/main/java/com/meiyun/finance/ReportCsvBuilder.java) L132 表头写 `毛利率(%)` 但 L144 输出：
```java
rate == null ? "" : rate.stripTrailingZeros().toPlainString()
```
`grossRate` 在 [RevenueMonthly.java](file:///Users/huluobo/WorkBuddy/2026-08-15-23-51-02/meiyun-platform/backend/finance-service/src/main/java/com/meiyun/finance/RevenueMonthly.java) 中为 `BigDecimal(6,3)` 存储小数（如 0.352），输出为 `0.352` 而非 `35.2%`。

**影响**：R02 门店经营月报 CSV 的毛利率列数据与表头单位不一致，用户读取时需自行换算。

**修复**：L144 改为 `rate.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%"`。

---

## §2 影响面

| 文件 | 改动 | 说明 |
|------|------|------|
| `FinanceAggregationService.java` L536-538 | +1 行 | mapPayMethod 加 `"transfer"` 分支 |
| `TripartiteReconcileService.java` L308-310 | +1 行 | 同上 |
| `FinanceInternalOpsService.java` L273-275 | +1 行 | 同上 |
| `ReportCsvBuilder.java` L144 | +2/-1 行 | 毛利率 ×100 百分比格式化 |
| `04-backlog.md` L132/L133 | 勾销 | B69 已施工，补勾销 |

**合计**：4 代码文件 +5/-2，1 docs 文件勾销 2 行。

---

## §3 卡序

**单卡批**：本批仅 1 卡。

### 卡1：mapPayMethod transfer 映射＋R02 毛利率%格式化＋L132/L133 勾销

**施工序**：
1. 三处 `mapPayMethod` 加 `"transfer" -> "transfer"` 分支
2. `ReportCsvBuilder` L144 毛利率百分比格式化
3. `04-backlog.md` L132/L133 补勾销（B69 已施工）
4. mvn compile 验证
5. 一卡一 feat commit/push
6. 批末台账五落点＋DELIVERY＋哨兵 docs 原子提交

**硬约束**：
- 零新表/零 Flyway/零新权限码/零新页面
- 同服务 JVM 直调不新增 internal HTTP
- 网关零改
- 金额 bigint 存分不变
- 数字 ✅109/🔧1/⬜55=166 不动（L132/L133 勾销不改变已完成计数，因 B69 已施工）

---

## §4 不做

1. **R03-R09 七模板接真**（L137）：各域业务表/聚合服务未就绪，远期
2. **XLSX·PDF 导出**（L138）：B69 已选型 EasyExcel+openPDF，但实施需新建 ReportBuilder 策略模式，属独立批
3. **R02 维度扩展**（L139）：revenue_monthly 维度字段补齐需月结流程先落地
4. **URGENT 加急标记源**（L143）：B51/B53 已定案诚实空态，不反复
5. **ai-service Flyway 悬置**（L130）：seed 库版本化专项，独立批
6. **mapPayMethod 其他渠道扩充**：当前仅 transfer 有明确业务需求，其余渠道（如信用支付）待产品定义
7. **R01 渠道分布表头修正**：R01 表头无单位问题，不涉及

---

## §5 关系

- **依赖 B69**：L132/L133 施工由 B69 卡1 完成（feat `363bb1d`＋fix `832a1fa`），本批仅补勾销
- **独立于 B71+**：R03-R09/XLSX·PDF/维度扩展属后续 M1 报表增强批，本批不涉

---

## §6 交付标准

1. 三处 `mapPayMethod` 统一映射 `"transfer"` → R01 银行转账不再归为"其他"
2. R02 CSV 毛利率列输出 `35.2%` 格式而非 `0.352`
3. 04-backlog L132/L133 勾销标记与 B69 施工事实一致
4. mvn compile 0 error
5. 数字 ✅109/🔧1/⬜55=166 不动

---

## §7 勾销

| 04-backlog 行 | 勾销类型 | 说明 |
|---|---|---|
| L132（M1 目标管理 REVENUE 实际值自动聚合） | ✅ 补勾销 | B69 卡1 已施工，本批补标记 |
| L133（M1 目标管理 REJECTED 终态不可再提交） | ✅ 补勾销 | B69 卡1 已施工，本批补标记 |
| L140（mapPayMethod transfer 渠道直通） | ✅ 勾销 | 本批卡1 施工 |
| L141（R02 毛利率%格式化） | ✅ 勾销 | 本批卡1 施工 |
