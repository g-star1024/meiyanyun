# 美研云 · 领域模型（基线草稿 v1）

> 状态：**草稿 / 待评审**（2026-08-24 已就首批 5 个关键问题形成决议，见 §5）。这是「产品架构基线（逻辑层）」的第一份宪法文件。
> 目的：在写任何页面之前，先统一"我们有哪些核心对象、它们长什么样、彼此什么关系"。
> 对标：复盘报告 §3 六家竞品共性范式；现有 `design-spec/` 335 屏模块划分（M1-M6/A1/T1-T4/G/C）。
> 配套文件：`business-flows.md`（状态机/流程）、`permission-matrix.md`（角色权限）。

---

## 0. 阅读约定

- **聚合根（Aggregate Root）**：跨页面流转、需要独立 ID 与生命周期的核心对象。
- **实体 / 值对象**：聚合根内部的组成部分。
- **模块映射**：与现有设计稿模块对齐，便于"屏 → 聚合"追溯。
- 本草稿只定义**结构与关系**，不定义接口字段（接口契约见后续 `api-contract.md`）。

---

## 1. 顶层聚合根清单

| # | 聚合根 | 中文 | 模块 | 一句话职责 |
|---|--------|------|------|-----------|
| 1 | `Organization` / `Store` | 集团 / 门店（租户） | M1 | 多租户边界，所有数据的 scope 归属 |
| 2 | `Staff` | 员工（角色载体） | M1 | 顾问/医生/前台/店长/集团管理员，权限依附于此 |
| 3 | `Customer` | 客户 / 会员 | M3 | 全生命周期主干的"主语"，唯一 CustomerID 贯穿全链路 |
| 4 | `Appointment` | 预约 | M4 | 到店意图的载体，连接渠道→接待→咨询 |
| 5 | `Arrival` | 到店 / 排队 | M4 | 客户实际到店的瞬时事件，排队候补的单元 |
| 6 | `Triage` | 分诊记录 | M4 | 到店后"分流给谁"的决策（顾问/医生/项目） |
| 7 | `Consultation` | 咨询单 | M4 | 一次咨询过程与结论，可派生方案/预约 |
| 8 | `TreatmentPlan` | 方案 / 开方 | M4 | 咨询产出的项目清单（可下单） |
| 9 | `Order` / `Payment` | 订单 / 收银 | M4 | 方案→成交，含卡项/单次/定金 |
| 10 | `Writeoff` | 划扣 / 核销 | M4 | 已购项目按次消耗，连接治疗执行 |
| 11 | `CashAsset` / `TimesAsset` | 储值卡 / 疗程次卡 | M3 | 客户资产：余额型与次数型分离，`AssetAccount` 做只读汇总视图 |
| 11b | `AssetAccount` | 资产账户（只读视图） | M3 | 聚合一个客户名下所有 CashAsset/TimesAsset，供画像/总览展示 |
| 11c | `Contract` | 合同 / 开卡协议 | M3/M4 | 长期履约约定：一个合同可对应多订单/多资产，承载退款条款与知情同意 |
| 11d | `AssetTransfer` | 资产转移 | M3/M4 | 客户间/跨店资产转移的申请→审批→执行留痕（本期登记，后置实现） |
| 12 | `EMR` | 电子病历 | M2 | 诊疗记录，合规留存 |
| 13 | `Followup` / `Recall` | 回访 / 复诊提醒 | M2 | 术后关怀与复购触发 |
| 14 | `Complaint` | 投诉 / 医疗风险 | M2 | 异常事件闭环 |
| 15 | `Refund`（含 `kind:ORDER/CARD`） | 退款 / 退卡 | M4 | 逆向交易共用聚合，需双签/分级审批（方案 A） |
| 16 | `MarketingCampaign` | 营销活动 | M5 | 获客与私域留存载体 |
| 17 | `InventoryItem` | 耗材 / 库存 | M1 | 供应链末端，划扣消耗对账 |
| 18 | `FinanceRecord` | 财务流水 | M6 | 营收/对账/目标 |
| 19 | `ComplianceEvent` | 合规 / 审计事件 | M1 | 签名/权限/操作留痕（含超管 impersonate 记录） |
| 20 | `CustomerMerge` / `Referral` | 撞单合并 / 转介绍 | M3 | 疑似重复关联 + 受控人工合并；转介绍归属关系（含有效期/审核） |

> 设计稿里的"屏"大致是上述聚合根的**列表/详情/编辑/看板**视图，因此一个聚合根通常对应 3-8 屏。

---

## 2. 核心实体属性（精简，仅供对齐，非最终 DTO）

### 2.1 Organization / Store（租户）
```
Organization { id, name, type: 'GROUP'|'BRAND'|'REGION'|'STORE',
               parentId?, brandId?, regionId?,
               status, timezone, createdAt }
Store        extends Organization { address, phone, businessHours, managerStaffId,
               brandId, channelConfig }
```
- **层级（已决议）**：集团 GROUP > 品牌 BRAND / 区域 REGION（二级，可并存）> 门店 STORE。
- **scope 锚点**：全系统数据以 `storeId` 为一级过滤；品牌看旗下门店、区域看下属门店、集团看全部（数据域见 permission-matrix）。

### 2.2 Staff（员工 = 角色载体）
```
Staff { id, storeId, name, avatarLetter, jobTitle,
        roles: Role[],         // 一对多，见 permission-matrix.md
        dataScope: 'SELF'|'STORE'|'REGION'|'GROUP',
        status, createdAt }
```
- **关键决策**：权限不挂在 `jobTitle` 上，挂在 `roles[]` 上。一个员工可有多个角色（如店长兼咨询师）。

### 2.3 Customer（客户 = 主干主语）
```
Customer { id(CustomerID), storeId, name, phoneEnc, avatarLetter,
          来源 channel: 'ONLINE_APPT'|'WALK_IN'|'REFERRAL'|'MARKETING',
          level: 'NEW'|'C'|'B'|'A'|'KA',          // RFM 分级
          tags: string[], portrait: Portait,      // 画像/撞单信息
          referralById?: CustomerID,              // 转介绍人（经审核确认后生效）
          referralExpiresAt?: Date,               // 转介绍归属有效期
          mergedFrom?: CustomerID[],              // 被合并进来的旧 ID（留痕可追溯）
          masterId?: CustomerID,                  // 非空=本条已被合并到 masterId（作废）
          ownerStaffId?: StaffID,                 // 归属咨询师（SELF 数据域依据）
          assets: AssetRef[],                      // 关联资产账户/合同/订单/病历
          lifecycleStage, createdAt, updatedAt }
Portait  { ageRange, skinConcerns[], firstVisitAt, lastVisitAt, totalSpend, visitCount }
```
- **贯穿全链路**：`CustomerID` 是预约→到店→咨询→开方→订单→划扣→回访的唯一关联键。
- **撞单（已决议）**：系统只做"疑似重复"**关联**（同手机/设备/证件），不自动合并；人工合并需 `customer:merge` 权限 + 审批，写入 `mergedFrom/masterId` 留痕、可回滚。

### 2.4 业务主链路实体
```
Appointment { id, customerId, storeId, type: 'FIRST'|'RETURN'|'SERVICE',
              consultantId?, doctorId?, timeSlot, status: ApptStatus,
              source, createdBy, createdAt }
Arrival     { id, appointmentId?, customerId, storeId, arrivedAt,
              channel, queueNo, status: 'WAITING'|'TRIAGED'|'CALLED'|'DONE'|'LEFT' }
Triage      { id, arrivalId, customerId, type: 'CONSULT'|'MEDICAL'|'SERVICE',
              assignedTo(staffId), forwardedTo?(staffId), note, editedBy?, editedAt }
Consultation { id, customerId, arrivalId?, consultantId, doctorId?,
               status: 'PENDING'|'ACTIVE'|'PLANNED'|'DONE'|'ABANDONED',
               conclusion, planId?, appointmentId? }
TreatmentPlan { id, consultationId, customerId, items: PlanItem[],
                totalAmount, status }
Order       { id, customerId, planId?, items, payType: 'CARD'|'CASH'|'MIX',
              amount, paidAt, cashierId, signTier, status }
Writeoff    { id, orderItemId, customerId, courseId?, executedBy(doctorId),
              verifyCode, signature, executedAt, status }
```
- 这些是**闭环的关键**：`Arrival` 由预约/自然到店产生，`Triage` 把它分流，`Consultation` 派生 `TreatmentPlan` → `Order` → `Writeoff`。

### 2.5 资产 / 合同 / 转移（已决议）
```
// 储值型资产（钱）
CashAsset   { id, customerId, storeId, balance, giftBalance, payType,
              contractId?, status, expiresAt? }
// 次数型资产（疗程/次卡）
TimesAsset  { id, customerId, storeId, itemSku, itemName, totalTimes,
              remainingTimes, contractId?, status, expiresAt? }
// 资产账户：只读聚合视图，汇总名下所有资产（不承载写逻辑）
AssetAccount{ customerId, cashAssets: CashAsset[], timesAssets: TimesAsset[],
              totalBalance, totalRemainingTimes }
// 合同：长期履约约定，一个合同可对应多订单、多资产
Contract    { id, customerId, storeId, no, type: 'CARD'|'COURSE'|'PACKAGE',
              amount, terms: ContractTerms, signedAt, status,
              orderIds[], assetIds[], consentDocUrl? }
ContractTerms { refundRule, validMonths, freezeAllowed, transferAllowed, clauses[] }
// 资产转移（客户间/跨店），本期登记、后置实现
AssetTransfer{ id, kind: 'CUSTOMER'|'STORE', assetId, fromCustomerId/toCustomerId?,
              fromStoreId/toStoreId?, reason, status: 'APPLIED'|'REVIEWING'|'APPROVED'|'DONE'|'REJECTED',
              approvedBy?, executedAt? }
// 撞单合并（人工受控）
CustomerMerge{ id, masterId, mergedIds[], reason, evidence, status,
               requestedBy, approvedBy?, executedAt? }
```
- **资产拆分理由**：余额型（原路退、利息/赠送金、支付方式）与次数型（绑项目、有效期、过期）规则差异大，分聚合内聚；`AssetAccount` 只做读模型汇总。
- **Contract 与 Order 关系**：Order 是"钱的事件"，Contract 是"履约的约定"；混合包（充值送疗程）= 一个 Contract 挂多个 Order + 多个 Asset。
- **AssetTransfer 后置**：依赖 Contract/Asset 先稳定；权限码 `transfer:*` 已保留。

---

## 3. 关系图（文字版）

```
Organization(集团)
 └─ Store(门店) ──< Staff(员工, 多角色)
       │
       ├─< Customer(客户) ──< Appointment(预约) ──> Consultation
       │     │                     │                     │
       │     │                     │                     └─> TreatmentPlan ─> Order ─> Writeoff
       │     ├─< Arrival(到店) ──< Triage(分诊) ──────┘
       │     ├─< Contract(合同) ──< Order(订单)
       │     │     └─< CashAsset/TimesAsset(资产) ── 被 Writeoff 消耗
       │     │             └─< AssetTransfer(转移, 后置)
       │     ├─< CustomerMerge(撞单受控合并)
       │     └─< EMR / Followup / Recall / Complaint
       │
       ├─< MarketingCampaign(获客→生成 Customer)
       ├─< InventoryItem(耗材, 被 Writeoff 对账)
       └─< FinanceRecord / ComplianceEvent(集团管控)
```

**关键外键（页间必须传递的 ID）**：
- `CustomerID`：客户列表 → 详情 → 预约 → 咨询 → 订单 → 病历，全程携带。
- `ArrivalID`：接待台 → 分诊 → 咨询工作台。
- `OrderID / TxnNo`：收银 → 双签 → 划扣核销。
- `storeId`：所有列表查询的隐式过滤条件（由登录角色 scope 决定）。

---

## 4. 与现有代码的映射（避免重复造轮子）

| 现有文件 | 对应聚合 | 备注 |
|---------|---------|------|
| `src/api/m4.ts` | Appointment/Order/Writeoff/Triage | 接口最完整，优先复用 |
| `src/api/txn.ts` | Refund/CardCancel/Sign | 已有 `createRefund` 等，接 `stores/txn.ts` |
| `src/api/m2.ts` | EMR/Followup/Recall | **当前是死桩**，需补实现 |
| `src/api/mgmt.ts` | Organization/Staff/Marketing | **当前是死桩**，需补实现 |
| `src/data/staff-seeds.ts` | Staff 种子 | 89 行顾问/医生名单，可转 seed |
| `src/stores/txn.ts` | 交易状态 | 唯一 store，应升级为领域状态层之一 |
| `ReceptionView.vue` | Arrival/Triage | 已有分诊 UI，需接 `Arrival` 状态机 |

---

## 5. 待你确认 / 开放问题（评审重点）

1. **~~多租户粒度~~（已决议 2026-08-24）**：集团下增加**品牌 BRAND / 区域 REGION** 二级，层级为 GROUP > BRAND/REGION > STORE；`Organization.type` 增加 `BRAND`，数据域相应增加 `BRAND`。
2. **~~Customer 唯一性 / 撞单消解~~（已决议 2026-08-24）**：以**关联为底座 + 受控人工合并**。系统只识别"疑似重复"建立关联，不自动合并；人工合并需 `customer:merge` 权限 + 审批，写 `mergedFrom/masterId` 留痕、可回滚。
3. **~~员工多角色~~（已决议 2026-08-24）**：允许一人多角色（如店长兼咨询师），权限取角色并集、数据域取最大范围；auth store 已落地 `currentRoles[]` + `toggleRole()`。
4. **~~资产模型~~（已决议 2026-08-24）**：拆为 `CashAsset`（储值/余额）与 `TimesAsset`（疗程/次数）两个独立聚合，加只读 `AssetAccount` 视图汇总；不合并为一个万能 Asset。
5. **~~逆向交易~~（已决议 2026-08-24，方案 A）**：退款与退卡**共用一个 `Refund` 聚合**，加 `kind: 'ORDER'|'CARD'` 区分；共用"申请→审核→分级审批→双签→到账"状态机；退卡的特殊性用更高 signTier + 倒扣规则表达，不拆两套流程。
6. **~~缺失聚合~~（已决议 2026-08-24）**：补 `Contract`（合同/开卡协议，独立聚合）与 `AssetTransfer`（资产转移，独立聚合但**后置实现**，本期先登记并保留 `transfer:*` 权限码）；补 `CustomerMerge`/`Referral` 支撑撞单合并与转介绍归属。
7. **~~自定义角色~~（已决议 2026-08-24）**：8 个内置角色不够，后续支持**自定义角色及权限**；auth store 已预留 `registerCustomRole(key, {label,permissions,scope})`，后续做"角色管理"页对接。

> 评审后我会据此产出 `src/types/domain.ts`（TS 类型）与 `src/stores/*`（领域状态层），直接落地到代码。
