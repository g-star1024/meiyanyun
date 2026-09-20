# B69 产品口径 / 状态机 / 选型决策 定案

> **批次**：P5-B69 ｜ **类型**：调研＋定案批（纯文档，零施工）
> **日期**：2026-09-20
> **范围**：从 04-backlog 筛选 19 项 B 类候选（口径/状态机/选型），逐项书面定案
> **铁律**：零新表 / 零 Flyway / 零新权限码 / 零新页面 / 零新路由 / 零新增 CSS

---

## §0 调研总述

### 0.1 调研方法

从 04-backlog 筛选全部 B 类候选项（口径定义 / 状态机设计 / 选型决策），按五个选型方向执行联网竞品调研：

| # | 选型方向 | 调研关键词 | 候选方案数 |
|---|---------|-----------|-----------|
| 1 | Java 规则引擎 | Drools / LiteFlow / Easy Rules / QLExpress / Aviator | 5 |
| 2 | 对象存储 | MinIO / 阿里云 OSS / 本地存储 | 3 |
| 3 | 客户去重/撞单合并 | Tilores / AesthetiDocs / Routine / HakLabs / Appmaster | 5 |
| 4 | 营销自动化 Flow | HubSpot / Salesforce / ActiveCampaign / Zoho / HighLevel | 5 |
| 5 | Java 报表生成 | Apache POI / EasyExcel / FastExcel / JasperReports / iText | 5 |

### 0.2 选型决策速览

| # | 选型方向 | 决策 | 理由（一句话） |
|---|---------|------|--------------|
| 1 | 规则引擎 | **不引入外部引擎** | 已有 SOP 引擎（B30/B31）+ 通知中心（B20/B26）+ 赠金规则（B26），内建轻量 Trigger-Condition-Action 即可 |
| 2 | 对象存储 | **本地存储 + StorageService 抽象** | 当前数据量小；MinIO 社区版 2025-12 进入维护模式，不增加运维负担 |
| 3 | 撞单合并 | **归一化手机号 + 确定性匹配 + Survivorship 规则** | 行业通用范式，B62 期1只读已就位，写路径按此定案 |
| 4 | 营销自动化 | **内建轻量 Flow，不采购外部 SaaS** | 外部 $14-25/user/month 不适合嵌入 B 端 SaaS |
| 5 | 报表导出 | **EasyExcel（XLSX）+ openPDF（PDF）** | 流式低内存；openPDF LGPL 无 AGPL 风险；不引入 JasperReports 重依赖 |

### 0.3 竞品调研明细

#### 0.3.1 Java 规则引擎

| 方案 | 特点 | 性能（10⁴执行） | 适用场景 | 结论 |
|------|------|----------------|---------|------|
| Drools | 重量级 BRMS，Rete 算法 | ~420ms | 企业级复杂规则 | ❌ 过重 |
| LiteFlow | 国产 Dromara，组件式编排，EL 表达式 | ~120ms | 流程编排 | ❌ 过度 |
| Easy Rules | ~100KB，POJO+注解 | ~38ms | 简单规则集 | ⚠️ 可考虑但非必需 |
| QLExpress | 阿里脚本引擎，热更新 | ~65ms | 动态脚本 | ❌ 脚本风险 |
| Aviator | 高性能表达式 | ~28ms | 表达式计算 | ❌ 非规则引擎 |

**决策**：美研云已有三套内建引擎（SOP/通知/赠金），新增自动化 Flow 需求可在现有模式上扩展 Trigger-Condition-Action 表，无需引入外部依赖。

#### 0.3.2 对象存储

| 方案 | 特点 | 运维成本 | 结论 |
|------|------|---------|------|
| MinIO | S3 兼容，单二进制，58.9K Star | 中（社区版进入维护模式） | ❌ |
| 阿里云 OSS | 托管，12 个 9 可靠性 | 低（持续费用） | ⚠️ 远期可选 |
| 本地存储 | 零依赖，低延迟 | 极低 | ✅ 当前最优 |

**决策**：定义 `StorageService` 接口（upload/download/delete/presignedUrl），当前实现 `LocalStorageService`（本地文件系统），远期可无缝切换 OSS/MinIO。

#### 0.3.3 客户去重/撞单合并

行业通用六步范式：

1. **归一化**：手机号去空白/连字符/国际前缀
2. **分桶**（Blocking Keys）：按归一化手机号分桶，避免 O(n²) 全量比较
3. **匹配**：确定性（精确匹配）→ 模糊（编辑距离/相似度）
4. **阈值分档**：≥0.9 自动合并 / 0.7-0.9 人工审核 / <0.7 拒绝
5. **Survivorship**：选主记录（创建时间最早 / 信息最完整）
6. **Lineage**：保留被合并记录溯源（audit_trail 记 merge_source）

**决策**：B62 期1只读已实现步骤①②③（归一化+分桶+确定性 0.95 分），写路径定案按步骤④⑤⑥扩展。

#### 0.3.4 营销自动化 Flow

| 方案 | 价格 | 结论 |
|------|------|------|
| HubSpot | $14-80/user/month | ❌ 外部 SaaS，不可嵌入 |
| Salesforce | $25-300/user/month | ❌ 同上 |
| ActiveCampaign | $15-80/user/month | ❌ 同上 |
| Zoho | $14-52/user/month | ❌ 同上 |
| HighLevel | $97-297/month | ❌ 同上 |

**决策**：全部外部 SaaS 不适合嵌入美研云。在已有 SOP 引擎 + 通知中心 + 赠金规则基础上，内建轻量 Trigger-Condition-Action 引擎。

#### 0.3.5 Java 报表生成

| 方案 | 特点 | 内存 | 许可 | 结论 |
|------|------|------|------|------|
| Apache POI | 功能全面 | 高（全量加载） | Apache 2.0 | ⚠️ 内存瓶颈 |
| EasyExcel | 阿里开源，流式 | KB 级 | Apache 2.0 | ✅ XLSX 首选 |
| FastExcel | 轻量 | 中 | Apache 2.0 | ⚠️ 生态不成熟 |
| JasperReports | 成熟引擎 | 高 | LGPL | ❌ 过重 |
| iText | PDF 专用 | - | AGPL | ❌ AGPL 风险 |
| openPDF | iText 分支 | - | LGPL | ✅ PDF 首选 |

**决策**：EasyExcel（XLSX）+ openPDF（PDF），在现有 `ReportCsvBuilder` 策略模式上扩展 `ReportXlsxBuilder` / `ReportPdfBuilder`。

---

## §1 口径定义定案

### 1.1 L132 REVENUE 实际值聚合口径

**现状**：`BizTarget.currentValue` 手工维护（管理指标），`revenue_monthly` 已有门店月度营收数据。

**定案口径**：

| 维度 | 定义 |
|------|------|
| 数据源 | `revenue_monthly` 表（`store_code` + `period_month` + `revenue`） |
| 聚合方向 | 门店 → 区域 → 集团（自下而上 SUM） |
| 时间匹配 | `revenue_monthly.period_month` 落在 `BizTarget.periodStart ~ periodEnd` 内 |
| 归属映射 | 门店 → 区域：通过 `org_unit.parent_code` 链路；区域 → 集团：顶层 `org_type=GROUP` |
| 聚合触发 | 读时计算（不写回 `currentValue`），保持 `currentValue` 手工维护语义不变 |
| 展示 | 在目标列表 API 中额外返回 `aggregatedRevenue` 字段（只读） |

**理由**：读时聚合避免双写一致性问题；`currentValue` 保留手工维护语义（可覆盖为手工调整值），`aggregatedRevenue` 为系统计算参考值。

### 1.2 L90 SOP 多模板口径

**现状**：
- 术后随访 SOP（txn-service）：**已支持**门店级模板（`storeCode` 为 NULL = 集团通用，有值 = 门店自建）
- 门店运营 SOP（store-service）：仅全局模板，无门店级覆写

**定案口径**：

| 概念 | 定义 |
|------|------|
| 全局模板 | `store_code = NULL`，集团统一定义，所有门店继承 |
| 门店模板 | `store_code = <门店编码>`，门店覆写全局模板 |
| 继承规则 | 门店模板存在则用门店版，否则 fallback 全局版 |
| 版本管理 | 模板发布时版本末位 +1（已有），门店覆写独立版本链 |
| 节点排序 | `SopTemplateStep.stepNo` 已有排序字段，拖拽排序即改 `stepNo` |

**施工约束**：门店运营 SOP 需新增 `storeCode` 字段（`sop_template` 表），但 B69 为零施工批，此条仅定案口径，施工归后续批次。

### 1.3 L40 sign_tier 旧阈值错配口径

**现状**：prod 8月已办结单使用旧 sign_tier 阈值，15 笔错配。

**定案口径**：

| 概念 | 定义 |
|------|------|
| 性质 | 数据治理问题（非代码缺陷） |
| 处理方式 | 编写数据治理 SQL 脚本，逐笔核实后修正 |
| 执行条件 | 须用户授权后在 prod 执行 |
| 审计 | 修正前后值均记 `audit_log` |

**施工约束**：不通过代码变更处理，独立数据治理专项。

### 1.4 L48 转介绍到期重分配口径

**定案口径**：

| 概念 | 定义 |
|------|------|
| 触发条件 | 推荐人离职/调岗 → 其名下待兑现转介绍失去有效归属 |
| 重分配规则 | 按原客户归属门店的当前有效顾问轮询分配 |
| 通知 | 重分配后通知新客户顾问 + 被介绍人（短信/微信） |
| 时效 | 转介绍有效期 90 天，超期自动标记失效 |

### 1.5 L80 沉睡客户唤醒口径

**定案口径**：

| 概念 | 定义 |
|------|------|
| 沉睡标准 | 180 天无到店 + 无消费记录 |
| 唤醒动作 | 自动触发短信/微信关怀（复用通知中心 B20/B26） |
| 频率 | 每客户最多 3 次，间隔 ≥ 30 天 |
| 退出 | 客户回复退订 / 已重新到店 → 退出唤醒队列 |

### 1.6 L91 随访结果结构化分析口径

**定案口径**：

| 概念 | 定义 |
|------|------|
| 结构化 Schema | 随访结果 = `{疼痛评分(1-10), 满意度(1-5), 并发症(枚举), 恢复评级(A/B/C/D), 备注}` |
| 数据来源 | 随访节点完成时填写（当前随访节点仅有完成/未完成） |
| 分析维度 | 按项目/门店/顾问/时间段聚合 |

**施工约束**：需扩展随访节点数据模型，归入数据/诊疗后续批。

### 1.7 L143 调度 URGENT 标记源口径

**定案**：维持 B68 诚实空态定案——不添加 URGENT 标记，前端展示真实调度状态。已闭合。

### 1.8 L34/L36/L37/L38 组织树二批口径

| 条目 | 口径定义 |
|------|---------|
| L34 兼岗模型 | 一个员工可归属多个组织节点（主岗 + 兼岗），数据权限取并集 |
| L36 REGION 审批路由 | 审批流按组织树层级（门店→区域→集团）自动路由，兼岗节点参与路由 |
| L37 层级写能力 | 区域/集团级节点可创建下级节点（当前仅允许门店下建部门） |
| L38 物理删除与编码回收 | 停用节点可物理删除，`org_code` 可回收复用（需审计记录编码历史） |

**施工约束**：四条目为独立大批次（涉及组织树核心模型变更），建议作为 B70+ 独立批次。

---

## §2 状态机设计定案

### 2.1 L133 目标管理 REJECTED 终态

**现状代码核实**：

`BizTargetService` 当前状态转换：
- `submit()`：仅 DRAFT → PENDING ✅
- `approve()`：仅 PENDING → APPROVED ✅
- `reject()`：仅 PENDING → REJECTED ✅
- `updateProgress()`：仅 APPROVED 可写 ✅

REJECTED 已是事实终态（无转出路径），但缺少：
1. 显式终态文档化
2. 修正出口（管理员重置为 DRAFT）

**定案状态机**：

```
DRAFT ──submit()──▶ PENDING ──approve()──▶ APPROVED ──updateProgress()──▶ (审计更新)
                       │                       │
                       └──reject()──▶ REJECTED  │
                                       │        │
                                       └──reset()──▶ DRAFT（管理员修正出口）
```

**reset() 设计**：

| 属性 | 值 |
|------|-----|
| 触发条件 | `REJECTED` 状态 |
| 权限 | `target:approve`（审批人专属） |
| 动作 | `approval` → `DRAFT`，清空 `submittedBy/submittedAt/approvedBy/approvedAt`，保留 `rejectReason`（审计溯源） |
| 审计 | `bizType=BIZ_TARGET, action=RESET`，payload 含 targetId + actor + 原 rejectReason |
| 前端 | REJECTED 行显示「退回修改」按钮（仅审批人可见） |

**理由**：REJECTED 保持终态（不可自行重新提交），但给审批人一个修正出口，避免"驳回后死锁"。

### 2.2 L44 撞单合并状态机

**现状代码核实**：

`MergeCandidateService`（期1只读）已实现：
- 归一化手机号查询（`findDuplicatePhoneRows()`）
- 按手机号分桶分组
- 数据域校验（`DataScope.canReadOwned`）
- 分类（POOL/SAME_STORE/CROSS_STORE）
- 确定性评分 0.95
- 手机号掩码输出

`CustomerMergeView.vue` 前端已建（展示疑似重复对 + 字段对比 + 选择保留方）。

**定案合并工作流状态机**：

```
DETECTED ──confirm()──▶ MERGING ──approve()──▶ MERGED
     │                       │
     └──dismiss()──▶ NOT_DUPLICATE
```

| 状态 | 含义 |
|------|------|
| DETECTED | 系统扫描发现的撞单候选对（当前 MergeCandidateService 输出） |
| MERGING | 用户确认合并，进入审批（锁定双方记录，禁止编辑） |
| MERGED | 审批通过，执行合并（子表 FK 迁移 + 审计） |
| NOT_DUPLICATE | 用户标记非重复（记录原因，不再提示） |

**Survivorship 规则**（合并时选胜值）：

| 字段 | 规则 |
|------|------|
| 主记录 | 创建时间最早的一方为胜方（winner） |
| 姓名 | 取 winner 值 |
| 手机号 | 取 winner 值（已归一化一致） |
| 客户等级 | 取较高者 |
| 归属顾问 | 取 winner 值 |
| 备注/标签 | 双方合并（union） |

**子表 FK 迁移**（8 张物理 FK 子表）：

```sql
UPDATE <child_table> SET customer_id = <winner_id> WHERE customer_id = <loser_id>;
```

迁移后 loser 记录标记 `anonymized=true`（不物理删除），`audit_log` 记录完整合并链路。

**审批流**：
- 同门店合并（SAME_STORE）：顾问自主合并，无需审批
- 跨店合并（CROSS_STORE）：需双方门店店长审批
- 公海合并（POOL）：操作顾问直属店长审批

### 2.3 L45 资产转移状态机（远期定案）

```
REQUESTED ──approve()──▶ TRANSFERRING ──complete()──▶ COMPLETED
      │                        │
      └──reject()──▶ REJECTED  └──fail()──▶ FAILED
```

| 状态 | 含义 |
|------|------|
| REQUESTED | 转移工单创建（顾问离职/调岗触发） |
| TRANSFERRING | 审批通过，执行资产迁移（客户/合同/储值卡/疗程卡） |
| COMPLETED | 全部资产迁移完成 |
| REJECTED | 审批拒绝 |
| FAILED | 迁移过程中异常（部分迁移需回滚） |

**施工约束**：独立批次，不在 B69 范围。

---

## §3 选型决策定案

### 3.1 L54 素材库对象存储

**现状代码核实**：
- `MarketingAsset`：仅管理元数据（assetName/type/tags/content），注释明确"不接 S3/MinIO 真实文件流"
- `CPhotoUpload.vue`：base64 dataURL 存前端，注释"生产替换为对象存储直传"

**定案选型**：本地存储 + `StorageService` 抽象接口

**接口设计**：

```java
public interface StorageService {
    String upload(String bucket, String key, InputStream data, String contentType);
    InputStream download(String bucket, String key);
    void delete(String bucket, String key);
    String presignedUrl(String bucket, String key, Duration expiry);
}
```

**当前实现**：`LocalStorageService`
- 存储根目录：`/data/meiyun-storage/`（可配置）
- 目录结构：`{bucket}/{yyyy}/{MM}/{uuid}-{key}`
- presignedUrl：返回本地 HTTP 下载链接（带 token 鉴权）

**远期切换**：实现 `OssStorageService`（阿里云 OSS）或 `MinioStorageService`，通过 `@ConditionalOnProperty` 切换，业务代码零改。

**理由**：
1. 当前数据量小（种子数据 + 演示），本地存储足够
2. MinIO 社区版 2025-12 进入维护模式，不推荐新引入
3. 接口抽象确保远期无缝切换

### 3.2 L138 报表 XLSX·PDF 导出

**现状代码核实**：
- `ReportCsvBuilder`：CSV 唯一输出，UTF-8 BOM + CRLF + RFC 4180
- `ReportJob.format`：已有 format 字段（当前固定 CSV）
- `ReportAsyncRunner`：异步生成 + BYTEA 存储 + SHA-256 指纹

**定案选型**：EasyExcel（XLSX）+ openPDF（PDF）

**扩展策略**：

| 组件 | 职责 |
|------|------|
| `ReportCsvBuilder` | 保持现有 CSV 输出（不变） |
| `ReportXlsxBuilder` | 新增，基于 EasyExcel 流式写入 XLSX |
| `ReportPdfBuilder` | 新增，基于 openPDF 生成 PDF |
| `ReportBuilder` 接口 | 统一策略接口：`String build(ReportTemplate tpl, List<Map> data)` |
| `ReportJob.format` | 扩展为 `CSV / XLSX / PDF` 三值 |

**理由**：
1. EasyExcel 流式解析，百万行 KB 级内存（vs POI 全量加载 GB 级）
2. openPDF 为 iText LGPL 分支，无 AGPL 商业风险
3. 策略模式确保现有 CSV 报表零改动

### 3.3 L49 私域自动化 Flow

**定案选型**：内建轻量 Trigger-Condition-Action 引擎

**设计蓝图**（远期实施）：

| 概念 | 定义 |
|------|------|
| Trigger | 事件源：客户建档 / 术后 N 天 / 沉睡 N 天 / 消费满额 / 生日 |
| Condition | 过滤条件：客户等级 / 门店 / 消费金额 / 标签 |
| Action | 执行动作：发送通知 / 创建任务 / 调整标签 / 触发 SOP |

**数据模型**（远期）：

```
automation_rule: id, name, trigger_type, trigger_config(JSON), condition(JSON), action(JSON), enabled, store_code
automation_log: id, rule_id, customer_id, triggered_at, action_result, status
```

**理由**：
1. 复用已有 SOP 引擎模式（模板 + 批次 + 定时巡检）
2. 复用通知中心（B20/B26）发送通道
3. 不引入 LiteFlow/Drools 等外部依赖

### 3.4 L53 营销归因模型

**定案选型**：多触点快照（不做归因计算）

**设计蓝图**（远期）：

| 概念 | 定义 |
|------|------|
| 触点记录 | 客户每次交互（广告点击 / 到店 / 咨询 / 消费）记一条触点 |
| 链路存储 | `customer_id → [touchpoint_1, touchpoint_2, ..., touchpoint_n]` |
| 归因延后 | 仅存储触点链路，不做首次/末次/加权归因计算 |

**理由**：
1. 医美行业决策周期长（平均 30-90 天），触点众多
2. 单一归因模型（首次/末次）失真
3. 先积累触点数据，远期再选归因算法

### 3.5 L130 ai-service Flyway 悬置

**定案选型**：seed 库版本化对齐

**设计蓝图**（远期）：
1. 梳理 ai-service V17-V29 迁移内容
2. 确认哪些已手动执行、哪些需补执行
3. 在 seed 环境统一跑 Flyway 对齐
4. 建立 prod/seed 版本对比监控

---

## §4 实施优先级与批次建议

### 4.1 B69 卡1 可施工项（近线）

| 条目 | 施工内容 | 预估改动 | 优先级 |
|------|---------|---------|--------|
| L133 | `BizTargetService.reset()` + 前端退回修改按钮 | ~50 行后端 + ~20 行前端 | 🔴 高 |
| L132 | 目标列表 API 返回 `aggregatedRevenue`（读时聚合） | ~80 行后端 | 🔴 高 |
| L54 | `StorageService` 接口 + `LocalStorageService` 实现 | ~150 行后端 | 🟡 中 |

### 4.2 独立后续批次

| 条目 | 建议批次 | 理由 |
|------|---------|------|
| L44 撞单合并写路径 | B70+ | 8 张子表 FK 迁移 + 审批流 + 审计，独立大批次 |
| L34/L36/L37/L38 组织树二批 | B70+ | 组织树核心模型变更（兼岗/层级写/物理删除），独立批次 |
| L90 SOP 多模板 | B70+ | `sop_template` 加 `store_code` + 继承逻辑，独立批次 |
| L138 报表 XLSX/PDF | B70+ | 新增 EasyExcel/openPDF 依赖 + Builder 策略，独立批次 |
| L45 资产转移 | B70+ | 独立状态机 + 合同/资产迁移逻辑 |

### 4.3 远期/数据治理（不排期）

| 条目 | 性质 | 处理 |
|------|------|------|
| L40 sign_tier 错配 | 数据治理 | 编写 SQL 脚本，用户授权后执行 |
| L48 转介绍重分配 | 远期营销 | 等自动化 Flow 引擎就绪后实施 |
| L80 沉睡唤醒 | 远期营销 | 等自动化 Flow 引擎就绪后实施 |
| L91 随访结构化分析 | 数据/诊疗 | 等随访数据模型扩展后实施 |
| L130 Flyway 悬置 | seed 版本化 | 独立运维操作 |
| L143 URGENT 标记 | 已闭合 | B68 诚实空态定案 |
| L49 自动化 Flow | 远期 | 先积累需求，等卡1 施工完成后评估 |
| L53 归因模型 | 远期 | 先积累触点数据 |

---

## §5 不做什么（诚实边界）

1. **不引入任何外部规则引擎**（Drools/LiteFlow/Easy Rules）——现有内建引擎模式足够
2. **不部署 MinIO**——社区版维护模式，当前数据量不需要
3. **不做归因计算**——仅存储触点快照，算法延后
4. **不在 B69 施工撞单写路径**——仅定案口径和状态机，写路径归后续批次
5. **不在 B69 施工组织树二批**——独立大批次
6. **不在 B69 施工 SOP 多模板**——独立批次
7. **不在 B69 施工报表 XLSX/PDF**——独立批次
8. **不处理 prod 数据治理**（L40）——独立脚本，非代码变更
9. **不新增任何数据库表或 Flyway 迁移**——B69 为零施工定案批

---

## §6 与既有批次的关系

| 关联批次 | 关系 |
|---------|------|
| B30/B31 术后随访 SOP | L90 多模板定案扩展其模型（storeCode 已有） |
| B33 组织树 | L34/L36/L37/L38 为其二批扩展 |
| B62 卡3 期1 撞单只读 | L44 写路径基于其 `MergeCandidateService` 扩展 |
| B65 营销散项评估 | L49/L53/L80 选型延续其"全留 Backlog"结论 |
| B68 营销残留清理 | L143 诚实空态已闭合 |

---

## §7 交付清单

| # | 交付物 | 状态 |
|---|-------|------|
| 1 | 本 DESIGN 定案文档 | ✅ |
| 2 | 5 项竞品调研结论 | ✅（§0.3） |
| 3 | 8 条目径定义 | ✅（§1） |
| 4 | 2 套状态机设计 | ✅（§2） |
| 5 | 5 项选型决策 | ✅（§3） |
| 6 | 实施优先级排序 | ✅（§4） |

---

## §8 勾销确认

- [ ] DESIGN 文档 docs 原子提交
- [ ] 台账更新（00-history / 01-dashboard / 03-timeline / 04-backlog / HANDOFF-AUTO）
- [ ] 转 B69 卡1 施工（L133 + L132 + L54）
- [ ] 卡1 完成后转卡2 三轨真验
