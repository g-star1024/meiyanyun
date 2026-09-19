# P5-B64 卡0（04-backlog L87/L90/L91 诊疗域收口批）—— 只读侦察与 B 类口径书面定案

> 时间：2026-09-20（夜间自主窗口，05:15 接力自 cc54da8，05:35 完稿）
> 状态：**方案定案（卡0 产出，定案前不写业务代码）**
> 用户决策原文（逐字，哨兵 B60 行保存的四阶段总授权）：
> 「1、先处理第二层近线，可以全部开始；2、然后处理core 库 24 行营销演示残留仿 B57 备份+物理删除；3、B. 产品口径 / 状态机 / 选型决策 按照你推荐的来就可以，做好竞品调研选择最差合适的就行；以上全部完成后转入C. 大阶段 / 专项开工」
> 事实基础：六册台账已读齐且三处勾稽（✅109 / 🔧1 / ⬜55 ＝166，约 66%）；04-backlog 全 152 行通读，定位诊疗域候选仅 L87/L90/L91 三行（行49 私域 Flow、行80 沉睡唤醒/复诊召回明确属「远期营销自动化批」，排除第四候选）；三候选经后端/前端/权限矩阵/网关只读侦察，事实链完整。
> 铁律遵循：真实库/接口>代码>文档（0）；API 三处一致（1）；中文枚举、金额存分（2/3）；Page+Specification（4）；前端真实 API（5）；构建部署（6）；curl+PG+Chrome 三轨真验（7）；一卡一 feat commit/push、代码与 docs 分离（8）；台账六册开发前读交付后写（9/10）；哨兵续跑（11）。
> 纵深批数字锁定：本批为**纵深收口批**，✅/🔧/⬜ 三个数字**一律不动**、无新页面；卡片闭合仅勾销 04-backlog 对应行，以本文档＋哨兵为施工唯一事实源。

---

## 〇、调研总述

### 0.1 候选甄别（04-backlog 逐字原文与批次标注）

| 行 | 事项（逐字要点） | 04 标注的建议批次 | 本批取舍 |
|---|---|---|---|
| L87 | M4Repurchase 复购方案签核 / 多级审批：单据内客户（预填）/经办/店长三方签核 B30 已闭合；**医师/店长/财务 ApprovalService 多级审批流仍留 Backlog** | **诊疗域收口批** | **本批唯一施工项**（卡1） |
| L90 | SOP 多模板 / 门店级模板 / 节点拖拽排序：现集团通用单模板 SPT-SEED-001（store_code 空），节点 lineNo 按 dayOffset 自动 renumber；门店自建模板、上下架/版本管理、手工拖拽未做 | **诊疗域后续批** | 延期，见 §7 |
| L91 | 随访结果结构化分析（满意度趋势 / 不良反应专项处置台）：stats 已提供 avgSatisfaction/adverseCount 实时计数；趋势分析与不良反应（adverse）专项处置工作台未做 | **数据/诊疗后续批** | 延期，见 §7 |

结论：04 原文仅 L87 标注本批，L90/L91 均明示「后续批」。B64 收口批＝L87 一行，不扩面。

### 0.2 竞品与合规调研

**A. 多级审批 / 分级授权（L87，两轮检索综合）**

| 来源 | 关键形态 | 本卡取舍 |
|---|---|---|
| 国内美业/医美 SaaS 厂商公开资料（美盈易/医美易、七韵数店等，2026-09 检索） | 退款/转卡通行**多级审批**「咨询师→财务→店长」、超限升级高层授权；频繁退款/深夜操作异常预警；操作权限最小化分级（前台仅看、财务可退、管理层可导出）；临时授权到期收回 | 采纳「超限才升级、按金额分层、最小授权」；异常预警/临时授权列远期 |
| 用友畅捷通等通用财务 | 审批流为平台通用模块、按金额/单据类型配置审批链 | 印证本卡复用统一 ApprovalService 而非复购自建审批链 |
| 行业合规标配 | 电子知情同意＋全链路审计追踪 | B30 知情同意硬前置＋三方签核已闭合，本卡只追加资金审批环节，不动既有同意链路 |

**B. SOP 多模板 / 术后随访（L90/L91 延期依据佐证）**

| 来源 | 关键形态 |
|---|---|
| PostCare（med spa aftercare，https://www.postcare.net/for/med-spas） | **按项目区分协议模板**（Botox/玻尿酸/激光等各自消息时间线与内容）；客户回报异常时 AI 按严重级别打标并即时通知团队 |
| Zenoti 术后跟进框架（https://www.zenoti.com/thecheckin/medspa-post-treatment-follow-up） | 术后五阶段自动序列（当日 4h 内 / 3–5 天 / 2–4 周 / 6–8 周 / 10 周+），按结账触发、按治疗项目个性化 |
| Healcon 医美 EMR（https://www.healcon.com/cosmetic-surgery-clinic-management-software） | 术后**并发症结构化记录**（伤口愈合阶段、肿胀、瘀青、引流量、感染征象、客户主诉），并发症率进质量报表；满意度按固定间隔（1 周/1 月/6 月/1 年）配置 |
| 客连连美业 SaaS（https://www.mekela.net/MedicalBeauty/Index） | 按项目自动生成回访任务（本次/计划/特殊回访）、回访预警、多角色协同 |
| **中国《医疗器械不良事件监测和再评价管理办法》（市监总局/卫健委令第1号，2019-01-01 施行，https://www.gov.cn/gongbao/content/2018/content_5343748.htm）** | 不良事件监测含「收集、报告、调查、分析、评价和控制」全过程；**群体不良事件 12 小时内电话报告＋24 小时内个例报告**；严重伤害有法定定义 |

要点：L90 行业方向（按项目多模板、时间线驱动）与本仓 store-service M1 SOP 已有的 version/status/applicableStores 模型吻合，后续批应做模型对齐而非临时加列；L91 的「不良反应专项处置台」在中国监管语境下不是普通 CRUD——含法定上报时限、严重程度分级、调查/评价/控制闭环，必须带产品口径专项决策，不宜在「收口批」顺带施工。两者延期既有 04 批次标注依据，也有合规复杂度佐证。

---

## 一、现有基础设施盘点（代码实证，2026-09-20 复核）

| # | 事实 | 位置 | 对本卡影响 |
|---|---|---|---|
| 1 | 审批引擎＝审批单即业务单：`approval_todo`（todoNo varchar(24) PK `AP+yyyyMMdd+6位` DB 当日序号、bizType、bizNo=业务单号、amount bigint 分、signTier、status PENDING/APPROVED/REJECTED/TRANSFERRED、stage 默认 REVIEW、payload TEXT、assignee、coSigners、history JSON） | txn-service `ApprovalTodo.java`、`ApprovalService.nextNo` L731-735 | 复购审批单复用此表，**零新表零 DDL** |
| 2 | bizType 为 **9 个字符串常量无枚举类**：REFUND/CARD_CANCEL/TRANSFER/LEAVE/PROCUREMENT/PRICE_CHANGE/LOSS_REPORT/REQUISITION/FIN_ADJUSTMENT，**无 REPURCHASE** | `ApprovalService.java`（767 行） | 新增第 10 个常量 `REPURCHASE`（字符串，与 BizType 枚举解耦的既有做法一致） |
| 3 | 金额阈值（分）：L1_MIN=100_000 / L2_MIN=500_000 / L3_MIN=2_000_000；`tierFor()`：<L1 不走审批，[L1,L2)→L1 财务单签，[L2,L3)→L2 店长+财务，≥L3→L3 插入区域段 | `TxnService.java` L33-35、L447-451 | 复购**直接复用 tierFor 与三级阶段机**，不自造阈值 |
| 4 | 阶段机 REVIEW（店长 STORE_MGR，SLA 24h）→ REGION（区域 REGION_MGR，SLA 8h，仅 L3 插入）→ FINANCE（财务，SLA 4h）；L1 单段从 FINANCE 起；priority ≥L3 HIGH | `ApprovalService` stageSlaHours L373-379、REGION 插段 L451-457 | 复购沿用；**医师不进入资金审批链**（见定案②） |
| 5 | B63 范式 `submitFinAdjustment` L160-208：findByBizNo 幂等回原单→tierFor→起始段（L1 FINANCE 否则 REVIEW）→priority→payload JSON→审计 SUBMIT；终审 L440-444 回调 financeAbnormalClient.applyResult（4xx 透传/5xx→502 回滚） | 同上、`FinanceAbnormalClient.java`、`InternalFinanceController.java` L450-480 | 复购照此范式新增 `submitRepurchase`；**同服务直调**复购完成逻辑，无跨服务 HTTP |
| 6 | 终审钩子 `deductOnFinalApprove` L338-366 现仅收 LOSS_REPORT/REQUISITION 两类耗材扣库（幂等键 todoNo）；reject L492-495；guardStageAssignee L629-655、guardTargetApprover L662-694 | `ApprovalService.java` | 复购终审/驳回在此分发，幂等模式照搬 |
| 7 | 复购无 Service 类：`M4RepurchaseController`（370 行，/api/txn）直接注入 RepurchaseRepository/DualSignTicketRepository/MemberCardRepository/AuditRecorder，**未注入 ApprovalService**；RP 单号用控制器实例 AtomicLong；POST /repurchase（followup:create，consentAck=false→400）；POST /repurchase/{no}/sign（followup:edit，三签非空/三人互异，通过即置「已完成」，**资产转移同事务并账 L155-181**，审计 REPURCHASE/CREATE·TRIPLE_SIGN·REJECT） | `M4RepurchaseController.java` L63-94、L118-188、L303-307 | 卡1 需把复购落单/签核/资产转移从控制器下沉为 `RepurchaseService`（薄控制器，铁律既有范式）；**大额资产转移后移到终审通过时点** |
| 8 | Repurchase 实体：repurchaseNo、customerId、storeCode、bizType（复购|资产转移）、targetProject、fromCardNo/toCardNo、transferTimes、**transferAmount Long（分，可空）**、consentAck、status 默认「待签核」、sign1/2/3＋sign1Role/2Role/3Role＋signedAt1/2/3 | `Repurchase.java` L47-75 | 审批金额取 transferAmount（null 按 0）；status 字符串值域追加「审批中」「已拒绝（终审）」语义，见定案②；ddl-auto=update 加值无需 DDL |
| 9 | txn-service **无 Flyway**（无 db/migration、pom 无依赖、application.yml `spring.jpa.hibernate.ddl-auto: update`）；全平台共享 Flyway 链现存最大 V39（finance），marketing B38/B48 有首引接入史 | `txn-service/src/main/resources/application.yml` L12-15；04 行94/97 | 本卡**不引 Flyway、不加表列**，彻底回避新基建；txn 首引 Flyway 留待 L90 等真正需要的后续卡（建议从 V40 起） |
| 10 | 权限矩阵**无 repurchase:\* 码、无 approval:approve 码**：复购复用 followup:view/create/edit；审批类级 approval:view，approve/reject/transfer/add-signer 均 `@RequirePerm({"refund:approve","cardcancel:approve"})`；STORE_MGR 有 approval:view＋两 approve＋followup 三码；FINANCE 有 approval:view＋两 approve；REGION_MGR 有 approval:view＋followup:view 但**两 approve 码未授权（待卡1 实测确认）**；OPERATOR 有 followup 三码但无 approval:view | org-service `PermissionMatrix.java`（approval:view L56、refund:approve L185、cardcancel:approve L186、followup L25/L154/L305）、`ApprovalController.java` | 定案④：**不新增权限码**；REGION_MGR 审批授权缺口为全 bizType 共性既存问题（B63 FIN_ADJUSTMENT 已走 L3 上线），卡1 先实测、若确为 L3 阻断则列入 04 新 backlog 行并按哨兵规则请用户拍板，不擅改跨域权限 |
| 11 | 审计 `AuditRecorder.record(bizType, txnNo, actor, action, payload)`，失败落 outbox，append-only；复购现用 bizType 字面量 "REPURCHASE"，审批流各 bizType 用各自字面量（如 FIN_ADJUSTMENT），动作 SUBMIT/APPROVE/REJECT/TRANSFER 等 | txn-service `audit/AuditRecorder.java`、`RestAuditRecorder.java` L43-50 | 定案⑤：复购单生命周期记 REPURCHASE（CREATE/TRIPLE_SIGN/APPROVAL_SUBMIT/APPROVAL_PASS/APPROVAL_REJECT），不新增审计基建 |
| 12 | 前端审批中心：ApprovalView 已有 FIN_ADJUSTMENT 标签 L44、权限映射 L55、DTO 映射约 L120、approveTodo L201、threeStage L402；stores/approval.ts（389 行）bizType 枚举 L17-27、BIZ_LABEL L69-79、BIZ_PERM L81-91、stagePerm L100-107（含本地种子/writeback 演示态）；api/approval.ts（98 行）ApprovalTodoDTO L9-32、GET /txn/approval L74-75 | `frontend/src/views/ApprovalView.vue`、`stores/approval.ts`、`api/approval.ts` | 定案⑥：加 REPURCHASE 分支三处（枚举/标签/权限映射），无新页 |
| 13 | 前端复购页：sign-grid 三 cell L365-383、foot-sign L389-396、v-perm followup:edit L398-402、consent-bar L334-338；stores/repurchase.ts create consentAck:true 硬编码 L170、元转分 L167-169；api/repurchase.ts 已有 | `frontend/src/views/RepurchaseView.vue`、`stores/repurchase.ts`、`api/repurchase.ts` | 定案⑥：详情区展示审批状态段/轨迹，审批中禁用三签/按钮；列表状态 pill 加新值，样式零改 |
| 14 | 网关前缀整段透传 `{"/api/txn","TXN_SERVICE_URL","http://127.0.0.1:8083"}` | `gateway/internal/proxy/router.go` L27-36 | 复购审批全部在 /api/txn 既有端点族内（POST /repurchase、POST /approval/...），**网关零改**；前端 nginx /api/ 反代零改 |
| 15 | C 端 /m/followup、/recall 纯前端 mock 后端零端点；customer-service 无随访域 | `MFollowupView.vue`、`RecallView.vue`、stores/followup.ts submitByCustomer L674-697 | 与 L87 无关；佐证 L91 后续批工作量（底座需新建），不在收口批展开 |

---

## 二、定案①：范围

- **本批（B64）唯一施工项＝L87**：M4 复购单在 B30 三方签核之外，衔接 ApprovalService 多级资金审批。卡1 一卡闭合（代码＋三轨真验＋feat 提交＋台账回写＋铁律 9 汇报）。
- **L90/L91 延期**：04 原文标注「诊疗域后续批」「数据/诊疗后续批」；行业调研显示 L90 应对齐 M1 SOP 版本/门店模型、L91 涉法定不良事件上报闭环，均超「收口」边界。本文档 §7 固化延期决策与后续批前置项，04 两行文字不改（其批次标注本就是结论）。
- 纵深批纪律：无新页面、无新路由、无 ⬜→✅、无 🔧 消解、完成度数字 109/1/55 不动；闭合动作＝04 L87 行尾勾销注记（仿 B63 勾 L86），事实以本文档＋哨兵为准。

## 三、定案②：产品口径与状态机

**审批触发金额口径**：审批针对**资金/资产划转风险**，金额取 `transferAmount`（bigint 分，null 按 0）。
- 纯方案复购（无划转金额，0 元）：维持 B30 现状——三方签核齐即「已完成」，**不生成审批单**。
- 有划转金额：阈值**完整复刻** tierFor（与退款/转卡/财务调整全平台一致，不自造阈值）：
  - ¥1,000 以下（<100_000 分）：不触发审批，三方签核齐即完成并账（现状）；
  - ¥1,000–¥4,999.99（L1）：生成审批单，**单段 FINANCE**（财务单签）；
  - ¥5,000–¥19,999.99（L2）：REVIEW（店长）→ FINANCE（财务）；
  - ≥¥20,000（L3）：REVIEW（店长）→ REGION（区域）→ FINANCE（财务），priority HIGH。
- **医师不进入资金审批链**：L87 原文「医师/店长/财务」中的医师职责已由 B30 三方签核（客户/经办/店长）与诊疗知情同意承载；审批引擎阶段机只有经营/财务岗，行业实践中医美退款/转卡审批亦为「咨询师→财务→店长/区域」经营链。不新增 PHYSICIAN 阶段。

**复购单状态机（中文字符串值域，延续 JPA 现状）：**

```
待签核（B30 现状，落单默认）
  │  三方签核完成（sign1/2/3 齐且互异、consentAck=true）
  ├─ 金额 < L1 ──────────────────────────────▶ 已完成（同事务资产转移并账，现状不变）
  └─ 金额 ≥ L1 ──┬─ 生成 approval_todo(bizType=REPURCHASE, bizNo=repurchaseNo)
                 ▼
              审批中（资产暂不转移；三签区/按钮禁用；可被审批流驳回）
                 ├─ 终审 APPROVED ─▶ 已完成（此刻同事务执行资产转移并账，记 APPROVAL_PASS）
                 └─ 任一级 REJECTED ─▶ 已拒绝（不并账；记 APPROVAL_REJECT；单据留痕不可重提，
                                        如需重做按新单号重新落单——与退款/转卡驳回口径一致）
```

- 幂等：`submitRepurchase` 先 `findByBizNo(repurchaseNo)`，已存在审批单直接回原单（仿 submitFinAdjustment），防三签重复提交产生多单。
- 审批中约束：复购单 RP 状态＝审批中时，/sign 端点拒绝再次签核（中文 422），资产转移只允许发生在「签核齐免审批」或「终审通过」两个时点。
- 驳回后资产零变动：fromCard/toCard 余额仅在终态「已完成」事务内变更；审批中异常断流不留半截动账。

## 四、定案③：复用底座

- 新增 `ApprovalService.submitRepurchase(...)`（同文件追加，仿 submitFinAdjustment L160-208）：幂等查单→tierFor(transferAmount)→起始段→payload JSON（建议含 repurchaseNo/customerId/storeCode/bizType/targetProject/fromCardNo/toCardNo/transferTimes/transferAmount/三签角色与时间/consentAck）→审计 SUBMIT。
- 终审/驳回分发：approve 分发 L407-468 增 REPURCHASE 分支——终审 APPROVED 同事务回调复购完成（资产转移），非终审按既有阶段推进；reject 分发 L492-495 增分支回写「已拒绝」。**同服务直调，不新增 internal 端点、不加 X-Internal-Token 链路**（FinanceAbnormalClient 是跨服务才需要，复购与审批同在 txn-service）。
- 控制器下沉：新建 `RepurchaseService`（txn-service 既有 Service 范式）承接落单/签核/审批衔接/资产转移，`M4RepurchaseController` 变薄；为规避 ApprovalService↔RepurchaseService 循环依赖，资产转移并账逻辑（现 L155-181）抽为包内组件，两处（免审批直完成、终审回调）共用。
- SLA、转交（transfer）、加签（add-signer）、候选人门店过滤、URGENT 通知全部沿用审批引擎既有能力，卡1 实测复购单在 DataScope/assignee 解析下的门店归属是否正确（payload.storeCode 与待办查询）。
- RP 单号沿用控制器实例 AtomicLong 现状（本卡不改为 DB 序号池，避免扩面；审批 AP 单号继续走 DB maxSeq）。

## 五、定案④：权限码

- **不新增任何权限码**（最小改动、与 B63 「新增码随实体走」不同——本卡零新实体）：
  - 发起复购/三方签核：沿用 followup:create / followup:edit（B30 现状，前端 v-perm 不变）；
  - 审批待办可见：approval:view（类级）；
  - 审批动作：沿用 `{refund:approve, cardcancel:approve}` 双码任一（ApprovalController 现状），前端 stores/approval.ts BIZ_PERM 增 REPURCHASE→该双码映射。
- **待实测风险（卡1 第一步）**：REGION_MGR 是否持两 approve 码之一。若未持有，则 L3 复购单 REGION 段无人可批——但该问题对 REFUND/CARD_CANCEL/FIN_ADJUSTMENT 所有 L3 单共性存在（B63 已上线），非本卡引入。处置：先以 curl 用 REGION_MGR 身份实测既有 L3 审批单；确认阻断则① 04-backlog 新增一行「REGION_MGR 审批动作授权缺口（全 bizType 共性）」，② 哨兵记为需用户拍板的跨域权限变更（属铁律「必须人决策的分叉」），本卡 L1/L2 路径照常闭合，不以临时放权绕过。
- OPERATOR 无 approval:view：维持——OPERATOR 可在复购页看到本单审批状态（随单查询，不进审批中心），但不获得审批中心列表/动作权限，符合最小授权。
- PermissionMatrix 若无需改动则不触发 RbacDataInitializer 重播种；JWT 重签仅在实测发现矩阵需调整时发生。

## 六、定案⑤：审计

- 不新增审计基建、不新增 audit bizType 常量类条目（复购现状本就是字符串字面量 "REPURCHASE"，与 SOP_TEMPLATE/FOLLOWUP 做法一致）。
- 复购单 bizType 字面量 `REPURCHASE` 下动作序列：`CREATE`（落单，现状）、`TRIPLE_SIGN`（三签齐，现状）、`APPROVAL_SUBMIT`（大额提交审批）、`APPROVAL_PASS`（终审通过并账）、`REJECT`（签核拒绝，现状）与 `APPROVAL_REJECT`（审批驳回，区分驳回环节）。
- 审批引擎自身对该单的流转记各 bizType 既有的 SUBMIT/APPROVE/REJECT/TRANSFER 审计（todoNo 维度），与复购单维度双轨留痕、append-only、失败落 outbox，全部不改。

## 七、定案⑥：前端挂载（零新页、零新路由、样式零改）

- `stores/approval.ts`：bizType 枚举 L17-27 追加 `'REPURCHASE'`；BIZ_LABEL L69-79 追加中文标签「复购/转卡审批」；BIZ_PERM L81-91 映射 `{refund:approve, cardcancel:approve}`；stagePerm 无需新增阶段。
- `ApprovalView.vue`：bizType 标签 L44 同款追加；DTO 映射约 L120 透传复购 payload 字段（复用通用 payload 渲染，不建专属详情模板）；threeStage L402 自动适配；列表页签/筛选随枚举自动出现。
- `RepurchaseView.vue`＋`stores/repurchase.ts`＋`api/repurchase.ts`：
  - 详情/列表状态 pill 支持「审批中」「已拒绝」（复用既有 pill 样式 class，不新增 CSS）；
  - 审批中：三签 sign-grid/foot-sign 与提交按钮禁用，展示当前审批段（店长/区域/财务）与历史轨迹（读 approval 详情或列表内 bizNo 匹配，卡1 择优，不新增后端查询端点——优先复用 GET /txn/approval 按 bizNo 过滤；若现接口不支持 bizNo 过滤则在既有 Specification 上加可选参数，三处一致）；
  - 终审通过后状态自然刷新为「已完成」；
  - 元转分 L167-169、consentAck:true L170 均不动。
- API 三处一致（铁律 1）：后端 Map DTO 字段 ↔ api/approval.ts、api/repurchase.ts 类型 ↔ store/视图消费，同名同型；中文枚举前后端逐字一致。

## 八、定案⑦：默认安全

- 不新增 /internal 端点、不新增服务间 RestTemplate 调用、不新增配置密钥；审批与复购同 JVM 直调。
- 网关零改（前缀已覆盖）；既有外网鉴权链（JWT＋@RequirePerm＋DataScope）全部生效；internal 路径继续外网裸 404 策略，与本卡无关。
- 幂等：findByBizNo 防重复审批单；资产转移仅两个终态时点触发；驳回不可逆且零动账。
- ddl-auto=update 下无结构变更（仅 status 字符串取值扩展，CHECK 约束现状不存在，卡1 以 `\d repurchase` 实证无阻断）；不加种子、不动 meiyun_seed 门控。
- 金额一律 bigint 存分，前后端元/分换算沿用 stores/repurchase.ts 既有函数。
- 不打印 payload 敏感明细到日志；审计 payload JSON 不落卡号全磁道（仅 fromCardNo/toCardNo 业务编号，现状如此）。

## 九、定案⑧：数据处置与三轨真验

- **卡0（本卡）纯只读＋docs**：零业务数据变更，无还原动作。
- 卡1 测试造数：仅 seed 双栈（seed 库，18083/18443/18080）；造数前对 repurchase、approval_todo 两表 CSV＋pg_dump 双份备份至 scripts/backup-*（不入库），仿 B57/B63。
- 测试覆盖四路径：①0 元/小于 L1 签核即完成且并账（回归 B30 不回退）；②L1 财务单签；③L2 店长→财务；④L3 店长→区域→财务（含 REGION_MGR 权限实测）；另测驳回不并账、重复提交幂等、审批中 /sign 被拒、OPERATOR 不可审批。
- 三轨真验（铁律 7）：
  - curl 经网关 -k 打 18443：落单→三签→（大额）审批单生成→逐段 approve/reject→查复购状态与余额；
  - PG：`docker exec meiyun-pg psql` 双库（-i）核对 approval_todo（bizType/bizNo/stage/signTier/amount/history）、repurchase（status/三签/金额）、member_card 余额仅在终态变动；审计表 REPURCHASE/审批动作齐条；
  - Chrome 双栈 18080：RepurchaseView 审批中禁用与轨迹、ApprovalView 复购分支标签/筛选/三段审批、v-perm 三角色（STORE_MGR/REGION_MGR/FINANCE/OPERATOR）可见性；截图受沙箱限时以 a11y 快照＋computed-style＋fetch CSS 原文取证（B63 后范式）。
- 还原：测试 RP 单及其 approval_todo/history 按单号单事务**物理 DELETE**（禁 TRUNCATE，ON_ERROR_STOP=1，DO 块断言影响行非零否则 RAISE 回滚）；member_card 测试前后余额快照比对，异常则备份恢复；审计 append-only 不删（历史断链 #380/#520 教训保留）。
- 构建部署（铁律 6）：txn-service mvn 打包＋容器/进程重启双栈；frontend pnpm vue-tsc＋vite 构建（禁 npm ci）；网关不动。
- 提交（铁律 8）：卡1 代码一个 `feat(txn): ...` commit＋push；台账/本文档更新 docs 单独 `git add -f` commit；哨兵心跳按小步独立提交。

## 十、L90/L91 延期决策与后续批前置项（固化，不在 B64 施工）

- **L90（SOP 多模板/门店模板/拖拽）→ 诊疗域后续批**。前置决策：① txn 随访 SOP 与 store M1 SOP（已有 version/status/applicableStores/sop:* 码）两套零共享，后续批先定「模型对齐或保持双轨」，不临时给 FollowupSopTemplate 加 status/version 列；② 多模板需要模板表结构与快照列（批次记模板号/版本），届时 **txn 首引 Flyway（建议 V40 起步，双库可重入纯 DDL，参 marketing B38/B48 接入史）**；③ 行业佐证：PostCare 78 个按项目协议、Zenoti 按治疗项目个性化时间线，均指向「项目维度模板」而非简单门店开关；④ FollowupScheduler.resolveNodes 已 union 全部 enabled 模板但不按门店过滤，门店过滤是后续批必补语义；拖拽排序改 renumber 机制需产品定交互。
- **L91（满意度趋势/不良反应专项处置台）→ 数据/诊疗后续批**。前置决策：① 不良反应在中国受《医疗器械不良事件监测和再评价管理办法》约束（群体事件 12h/24h 上报、严重伤害法定定义、收集-报告-调查-分析-评价-控制闭环），专项台必须含严重程度分级、处置状态机、责任人/时限、上报记录，属必须人决策的产品口径；② Followup 现状仅 adverseReaction boolean＋adverseNote，stats adverseCount 无时间维度，list 无 adverse 过滤——后续批需补结构化字段（Flyway 同期）＋趋势 stats 时间维度＋工作台；③ C 端 /m/followup、/recall 后端零端点，客户自报不良反应入口需新建（customer-service 或 txn 域归属待定）；④ 行业佐证：Healcon 并发症结构化字段＋质量报表、PostCare 异常自动分级通知。
- 两行 04 原文保持不动（批次标注即结论）；后续批开工时另发 DESIGN 卡0。

## 十一、卡1 施工序（夜间窗批准后执行）

1. 实测前置：REGION_MGR approve 权限（定案④风险）、`\d repurchase` 无 status CHECK、GET /txn/approval 是否支持 bizNo 过滤；
2. 后端：抽 RepurchaseService＋资产转移组件；ApprovalService 加 REPURCHASE 常量＋submitRepurchase＋终审/驳回分发；控制器变薄；
3. 后端构建＋双栈部署；
4. 前端：stores/approval.ts 三映射、ApprovalView 分支、RepurchaseView 状态/禁用/轨迹、api 类型三处一致；pnpm 构建部署；
5. 造数备份→四路径＋四负向 curl/PG/Chrome 三轨真验；
6. 单事务物理还原＋余额快照比对；
7. feat 代码 commit/push → 六册回写（04 L87 勾销、03 timeline、00/01/02 通读勾稽，数字不动）→ docs `git add -f` 单独 commit/push；
8. 哨兵改写：卡1 闭合则 B64 队列完成（L90/L91 不在本队列），按铁律 11 评估置 DONE 并铁律 9 汇报（整体完成度 X/Y 模块 Z%，本批新增 0 页面/勾销 1 backlog 行）。
