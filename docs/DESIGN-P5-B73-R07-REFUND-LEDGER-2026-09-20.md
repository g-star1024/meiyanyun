# DESIGN-P5-B73：R07 退款与纠纷台账接真

> **卡号**：P5-B73（M1 纵深批第四卡，R03-R09 七模板接真第一卡）
> **日期**：2026-09-20
> **状态**：定案待施工

---

## §1 现状与缺口

### 1.1 报表中心当前能力

| 项 | 现状 |
|----|------|
| 模板注册 | `report_template` 表 9 行（R01-R09），种子数据完整 |
| 已支持 | R01 门店营收日报（B71）、R02 月度经营分析报告（B71+B72 区域维度扩展） |
| 未支持 | R03-R09 全部 422 `"该模板数据源待建，已登记 backlog"` |
| 架构 | 策略模式 ReportBuilder（CSV/XLSX/PDF）+ ReportDataCollector + ReportBuilderRegistry |
| backlog | 04-backlog L137 `M1 报表 R03-R09 七模板数据源待建` |

### 1.2 R07 模板需求（种子数据定义）

| 属性 | 值 |
|------|-----|
| 名称 | 退款与纠纷台账 |
| 分类 | FINANCE |
| 周期 | MONTH |
| 维度 | 门店、退款原因 |
| 指标 | 退款笔数、退款金额、处理时长 |

### 1.3 数据源侦察结论

**TxnRefund 实体**（txn-service）字段完美匹配 R07 全部需求：

| 字段 | 类型 | 报表用途 |
|------|------|----------|
| `storeCode` | String(32) | 门店维度 |
| `reason` | String(64) | 退款原因维度 |
| `refundAmt` | Long | 退款金额（分） |
| `status` | String(20) | 区分 REFUNDED（已退）/ PENDING_* / REJECTED |
| `createdAt` | OffsetDateTime | 月过滤 |
| `refundedAt` | OffsetDateTime | 处理时长 = refundedAt - createdAt |
| `channel` | String(16) | 退款渠道（ORIGINAL/CASH/TRANSFER） |
| `customerName` | String(64) | 客户名（可选展示） |

**结论：零新表、零 Flyway，纯纵深批。**

---

## §2 口径定义

### 2.1 期间过滤

按 `createdAt` UTC 日界：`month` 参数（yyyy-MM）→ 月初 00:00:00Z 至月末 23:59:59Z。

### 2.2 聚合维度

`GROUP BY storeCode, reason`，每行输出：
- 退款笔数（count）
- 退款总金额（sum refundAmt，Long 分）
- 平均处理时长（仅 REFUNDED 终态：refundedAt - createdAt，天为单位，一位小数）

### 2.3 数据权限

- txn-service 内部端点以 system(GROUP) 身份全量返回
- finance-service ReportDataCollector 按 `DataScope.canReadStore(storeCode)` 逐行收敛
- 门店名解析走既有 `resolveStoreNames()`

### 2.4 金额口径

- 数据源 Long「分」→ 报表展示「元」（/100.0，两位小数）
- 仅计 `refundAmt`（实际退款金额），不计 `fee`（手续费独立字段）

---

## §3 不动项

- 零新表 / 零 Flyway
- 零新权限码（复用 `internal:finance-flow`）
- 零新页面 / 零新路由 / 零新增 CSS
- 零网关改动
- ReportBuilder 策略链不动（CSV/XLSX/PDF 三格式自动适配新 collect 方法）
- 数字 ✅109/🔧1/⬜55=166 一律不变

---

## §4 不做项

- 不接纠纷工单（TxnRefund 无纠纷子表，纠纷属远期 M2 工单域）
- 不接退款原因标准化（reason 为自由文本 String(64)，不做枚举归一）
- 不接退款渠道维度（channel 字段存在但 R07 种子定义不含渠道维度，保留不扩）
- 不做退款明细行级展示（报表为聚合视图，明细走 /finance/refunds 页面）

---

## §5 施工清单

### 卡1 施工项（3 项）

#### ① txn-service：新增 `GET /api/txn/internal/refund-summary` 内部端点

**文件**：`InternalFinanceController.java`

```
@GetMapping("/refund-summary")
@RequirePerm("internal:finance-flow")
public List<Map<String, Object>> refundSummary(@RequestParam String month)
```

- 参数：`month`（yyyy-MM），解析为月初/月末 OffsetDateTime 范围
- 查询：TxnRefundRepository + Specification（createdAt 区间）
- 聚合：Java 层 GROUP BY (storeCode, reason)
- 输出：`[{storeCode, reason, count, totalRefundAmt, avgProcessingDays}]`
- avgProcessingDays 仅算 REFUNDED 终态（refundedAt 非空），天为单位一位小数

#### ② finance-service：FinanceAggregationService 新增 fetchRefundSummary

**文件**：`FinanceAggregationService.java`

- 新方法 `fetchRefundSummary(String month)`
- RestTemplate + X-Internal-Token + UriComponentsBuilder（与 fetchFlows 同范式）
- 降级：txn 不可用 → 空列表，log.warn 不抛

#### ③ finance-service：ReportDataCollector 新增 collectR07

**文件**：`ReportDataCollector.java`

- 新增 `collectR07(String month)` 方法
- 调 `aggregation.fetchRefundSummary(month)` 取聚合数据
- `DataScope.canReadStore(storeCode)` 逐行过滤
- `resolveStoreNames()` 解析门店名
- 表头：`门店, 退款原因, 退款笔数, 退款金额(元), 平均处理时长(天)`
- switch 增加 `case "R07" -> collectR07(period)`

**文件**：`ReportService.java`

- SUPPORTED 从 `Set.of("R01", "R02")` 扩为 `Set.of("R01", "R02", "R07")`

---

## §6 三轨真验计划

### 轨1 curl 接口

```bash
# seed 栈 E011 华东（SST01/02/06）
# 1. 预览 R07（2026-09）
curl -s -H "Authorization: Bearer $E011_TOKEN" \
  "http://127.0.0.1:18443/api/finance/report/preview?templateId=R07&period=2026-09" | jq .

# 2. 生成 XLSX
curl -s -H "Authorization: Bearer $E011_TOKEN" \
  "http://127.0.0.1:18443/api/finance/report/generate" \
  -d '{"templateId":"R07","period":"2026-09","format":"XLSX"}' | jq .

# 3. 数据域收窄验证：SST03 退款不应出现在 E011 结果中
```

### 轨2 PG 对账

```sql
-- seed meiyun_txn：2026-09 退款按门店+原因聚合
SELECT store_code, reason, count(*) AS cnt,
       sum(refund_amt) AS total_fen,
       avg(CASE WHEN status='REFUNDED' AND refunded_at IS NOT NULL
           THEN EXTRACT(epoch FROM refunded_at - created_at)/86400 END) AS avg_days
FROM txn_refund
WHERE created_at >= '2026-09-01' AND created_at < '2026-10-01'
GROUP BY store_code, reason
ORDER BY store_code, reason;
```

### 轨3 Chrome 前端

- 报表中心 → R07 退款与纠纷台账 → 选 2026-09 → 预览 → 导出 XLSX/PDF
- 验证域内门店可见、域外门店不可见
- 验证金额格式（两位小数元）
- 验证处理时长格式（一位小数天）

---

## §7 数字影响

| 指标 | 值 |
|------|-----|
| ✅ 已完成 | 109（不变） |
| 🔧 进行中 | 1（不变） |
| ⬜ 待办 | 55（不变） |
| 合计 | 166（不变） |
| 新增页面 | 0 |
| 新增表 | 0 |
| 新增 Flyway | 0 |
| 新增权限码 | 0 |
| 新增路由 | 0 |
| 新增 CSS | 0 |
| 🔧 消解 | 0 |
