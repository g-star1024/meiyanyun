# 美研云 · 业务流 / 状态机（基线草稿 v1）

> 状态：**草稿 / 待评审**。产品架构基线第二份宪法文件。
> 目的：定义"一条业务从开始到结束，经过哪些状态、谁在什么节点操作、状态如何合法迁移"。
> 这是解决"页面没有逻辑、步步要人喂"的**核心**——只要状态机定了，页面就只是状态机的可视化。
> 配套：`domain-model.md`（对象）、`permission-matrix.md`（谁能动）。

---

## 0. 总原则

1. **一条主干**：所有功能挂在"客户全生命周期"这一条主干上，不按屏幕平铺。
2. **状态机驱动页面**：每个聚合根有显式 `status` 枚举 + 合法迁移表；页面只渲染"当前状态允许的下一步"。
3. **跨页靠 ID 流转**：`CustomerID / ArrivalID / OrderID` 在页间携带（见 domain-model §3）。
4. **异常有闭环**：候补、改约、退卡、投诉都是一等状态，不是"隐藏分支"。

---

## 1. 客户全生命周期主干（唯一主干）

```
[获客] → [预约] → [到店/排队] → [分诊] → [咨询] → [开方/方案]
   ↑                                                          │
   │                                                          ↓
[复购/转介绍] ← [回访/复诊] ← [治疗划扣] ← [收银成交] ← [方案确认]
```

| 节点 | 入口屏 | 聚合 | 关键动作 | 下一节点 |
|------|--------|------|---------|---------|
| 获客 | 营销中心 / 小程序 | MarketingCampaign → Customer | 留资、领券、转介绍 | 预约 |
| 预约 | 预约看板 / 新建预约 | Appointment | 选顾问/医生/时段 | 到店 |
| 到店 | 接待台 / 自助签到 | Arrival | 扫码/人脸/登记 | 分诊 |
| 分诊 | 接待台（改分诊） | Triage | 分流顾问/医生/项目 | 咨询 |
| 咨询 | 咨询工作台 | Consultation | 面诊、建档、出方案 | 开方 |
| 开方 | 项目开方开单 | TreatmentPlan | 生成项目清单 | 收银 |
| 收银 | 收款收银 | Order/Payment | 开卡/单次/定金、电子签 | 划扣 |
| 划扣 | 划扣核销 | Writeoff | 按次消耗、验证码+签名 | 治疗完成 |
| 回访 | 术后回访 | Followup/Recall | 满意度、复诊提醒 | 复购 |
| 复购 | 会员/营销 | Course/Card/Marketing | 触发再次预约 | 回到预约 |

---

## 2. 核心状态机（合法迁移表）

### 2.1 Appointment（预约）
```
NEW ──确认──> CONFIRMED ──到店──> ARRIVED
  │              │                  │
  │改约          │改约              │未到(NO_SHOW)
  ↓              ↓                  ↓
CANCELLED    RESCHEDULED       COMPLETED / NO_SHOW
```
- 迁移触发：NEW→CONFIRMED（顾问/前台）；CONFIRMED→ARRIVED（接待台签到）；ARRIVED→NO_SHOW（超时）。

### 2.2 Arrival / 排队候补（实时）
```
WAITING ──分诊──> TRIAGED ──呼叫──> CALLED ──进入咨询──> DONE
   │                │                                      │
   │弃号            │改分诊                                 │离店
   ↓                ↓                                      ↓
LEFT             TRIAGED(重分流)                         LEFT
```
- **候补逻辑**：WAITING 超时可自动进入候补队列，释放号源给下一位（queue 屏的"智能候补"）。

### 2.3 Triage（分诊，可改）
```
CREATED ──分配──> ASSIGNED ──改派──> RE_ASSIGNED ──确认──> ACTIVE
                              │
                              └─ 记录 editedBy/editedAt（接待台"改分诊"按钮）
```
- **竞品对齐**：企雀"分角色协同"——分诊把客户路由给顾问/医生/服务，而非前台手动跟。

### 2.4 Consultation（咨询）
```
PENDING ──开始──> ACTIVE ──出方案──> PLANNED ──确认──> DONE
   │                │                  │                  │
   │放弃            │放弃              │放弃              │转订单
   ↓                ↓                  ↓                  ↓
ABANDONED       ABANDONED          ABANDONED          Order(创建)
```

### 2.5 Order / Payment（收银，分级双签）
```
DRAFT ──提交──> PENDING_SIGN(L1/L2/L3) ──签署──> PAID ──核销中──> SETTLED
                  │                                        │
                  │驳回/超时                                │退款
                  ↓                                        ↓
               CANCELLED                                 REFUNDING
```
- **双签分级**（`stores/txn.ts` 已有 `signTier` L1/L2/L3）：金额越大，所需签署层级越高。

### 2.6 Writeoff（划扣核销）
```
CREATED ──执行──> EXECUTING(验证码+签名) ──完成──> DONE
   │                                            │
   │异常/禁忌硬阻断                              │余次-1
   ↓                                            ↓
ABNORMAL(触发 Complaint/EMR)                 Course.remaining--
```

### 2.7 Refund / CardCancel（逆向交易，**共用聚合 · 方案 A 已决议**）
```
APPLIED ──审核──> REVIEWING ──分级审批──> APPROVED ──双签──> REFUNDED
   │                  │                        │
   │驳回              │驳回                    │拒签
   ↓                  ↓                        ↓
REJECTED          REJECTED                 CANCELLED
```
- **共用一个 `Refund` 聚合**，`kind: 'ORDER'|'CARD'` 区分订单退款与退卡；二者走同一状态机与同一套审批/双签/对账。
- **退卡特殊性**用规则表达：`kind=CARD` 时默认更高 signTier、并执行余额/剩余次数冲销（已赠项目、已享折扣按规则倒扣）。
- 与现有 `stores/txn.ts`、后端 `txn-service` 的退款/退卡合一实现一致。

### 2.8 Contract / 资产 / 转移（已决议）
```
TreatmentPlan/Order ──> Contract(DRAFT→SIGNED) ──┬─> CashAsset(余额，充值/赠送)
                                                 └─> TimesAsset(次数，项目/有效期)
CashAsset/TimesAsset ──被 Writeoff 消耗；Contract 承载退款条款（Refund 计算依据）
AssetTransfer: APPLIED ──> REVIEWING ──审批──> APPROVED ──执行──> DONE
                     └─驳回──> REJECTED（客户间/跨店，后置实现，本期登记权限）
```
- 一个 Contract 可对应多个 Order（分期付款）与多个 Asset（充值送疗程混合包）。
- 退款算"扣多少"依据 `Contract.terms.refundRule`；资产转移影响门店间结算，必须审批留痕。

### 2.9 撞单合并（已决议：关联为底座 + 受控合并）
```
系统识别(同手机/设备/证件) ──> SuspectLink(疑似重复关联，只读提示)
店长/以上(customer:merge) ──发起合并──> CustomerMerge(REVIEWING)
审批 ──> APPROVED ──执行：主记录保留 masterId，旧记录写 mergedFrom 并作废
                       └─可回滚（保留映射，老订单仍可追溯原 CustomerID）
```
- 系统**永不自动合并**（医疗+资金安全）；合并需人工 + 审批 + 留痕。

### 2.10 转介绍归属（已决议：SELF 含经审核转介绍）
```
老客A(归属咨询师X) 推荐 新客B(channel=REFERRAL, referralById=A)
B 到店/确认 ──审核通过──> B.ownerStaffId = X（在 referralExpiresAt 有效期内）
有效期满 / B 申诉 ──> B 进入公海或重新分配
```
- 咨询师 SELF 数据域**包含经审核确认的转介绍客户**；转介绍需凭证 + 被介绍人确认，防抢客作弊。

---

## 3. 集团管控流（M1，多租户一等公民）

```
集团管理员 ──建门店/区域──> Organization
     │
     ├─ 门店对标(m1-compare)：聚合各 Store 的 FinanceRecord
     ├─ 调度中心(m1-dispatch)：跨店医生/设备排班
     ├─ 合规中心(m1-compliance)：汇总各 Store 的 ComplianceEvent
     ├─ 审计日志(m1-audit-log)：全集团操作留痕
     └─ 超管控 Impersonate：超管可切换任意门店/员工视角（默认只读、二次认证、全程水印+留痕，写入 ComplianceEvent）
```
- **数据域**：门店只看本店；品牌看旗下、区域看下属、集团看全部（`permission-matrix.md` 的 dataScope）。

---

## 4. 私域留存流（复购引擎）

```
成交(Course/Card) ──> 企微/小程序绑定 ──> 自动化营销 Flow
                                     │
                                     ├─ 生日/术后关怀(Push)
                                     ├─ 复诊提醒(Recall→Appointment)
                                     └─ 转介绍激励(新 Customer, channel=REFERRAL)
```
- 对标 Phorest TreatCard / Mangomint 自动化 flow：留存是系统行为，不是人工提醒。

---

## 5. 跨页数据契约（屏 → 路由 → ID）

| 起点屏 | 动作 | 携带 ID | 落地屏 |
|--------|------|---------|--------|
| 预约看板 | 点客户 | CustomerID | 客户画像 |
| 接待台 | 分诊 | ArrivalID, CustomerID | 咨询工作台 |
| 咨询工作台 | 出方案 | ConsultationID | 项目开方开单 |
| 开方开单 | 提交 | PlanID | 收款收银 |
| 收款收银 | 双签 | OrderID/TxnNo | 划扣核销 |
| 划扣核销 | 完成 | WriteoffID | 疗程跟踪 |

> 这是当前最大的 gap：报告 §1.2 指出"预约号/客户ID/订单号无法在页间传递"。状态机 + 共享领域 store 解决它。

---

## 6. 角色协同时序（以"到店分诊"为例）

```
客户 ──到店──> [接待台/自助Kiosk] ──创建 Arrival(WAITING)
前台 ──分诊──> Triage(ASSIGNED → 顾问A)
顾问A ──咨询──> Consultation(ACTIVE → PLANNED)
顾问A ──开方──> TreatmentPlan → Order(PAID)
收银 ──核销──> Writeoff(DONE) → Course.remaining--
医生 ──病历──> EMR
护士/系统 ──回访──> Followup → Recall
```
- 每个箭头 = 一次状态迁移 = 一个有权限的角色点一次按钮。页面只需把"当前角色此刻能点的按钮"渲染出来。

---

## 7. 待你确认 / 开放问题

1. **候补策略**：WAITING 超时多久进候补？是否自动释放号源？
2. **改分诊边界**：接待台"改分诊"能否跨门店改派？还是仅本店内？
3. **双签阈值**：L1/L2/L3 的金额阈值按门店还是集团统一配置？
4. **~~逆向交易~~（已决议 2026-08-24，方案 A）**：退卡与退款**共用**一个 `Refund` 聚合 + `kind` 区分，共用审批/双签状态机；退卡通过更高 signTier + 余额倒扣规则加严，不另起流程。
5. **~~闭环样板~~（已落地 2026-08-24）**：主线 **到店→分诊→咨询→生成预约**，见 `/closed-loop` 与 `stores/clinic.ts`。
6. **~~撞单 / 资产 / 合同 / 转移 / 转介绍 / impersonate~~（已决议 2026-08-24）**：见 §2.8–§2.10 与 §3——资产拆 CashAsset/TimesAsset、Contract 独立、AssetTransfer 独立后置、撞单受控合并、SELF 含审核转介绍、超管受控 impersonate。

### 仍待确认
1. **候补策略**：WAITING 超时多久进候补？是否自动释放号源？
2. **改分诊边界**：接待台"改分诊"能否跨门店改派？还是仅本店内？
3. **双签阈值**：L1/L2/L3 的金额阈值按门店还是集团统一配置？
4. **转介绍归属有效期**：`referralExpiresAt` 默认多长？到期自动进公海还是需人工确认？
