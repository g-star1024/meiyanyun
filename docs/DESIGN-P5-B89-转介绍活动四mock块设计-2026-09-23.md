# DESIGN-P5-B89 转介绍活动四 mock 块后端化设计（2026-09-23）

> 批定义（04-backlog L157 登记行）：转介绍活动配置/阶梯奖励/层级奖励/邀请排行四 mock 块（/m5-referral 活动侧）后端化。
> 来源：P5-B86 卡3 移交（DESIGN-P5-B86 §6，铁律 5.1 显式标注保留 mock，v1 无活动实体）。
> 纵深批：✅/🔧/⬜ 数字不变（B50-B79 先例），仅 04 L157 勾销。

---

## 1. 卡0 侦察结论

### 1.1 四 mock 块链路映射

| Mock 块 | 前端现状（View / store） | 后端化目标 |
|---|---|---|
| ①活动配置 campaigns | m5ReferralCampaign.seed() 灌 6 活动；**无列表 UI**，仅喂 KPI1「进行中邀请活动」ongoingCount＋KPI2「累计邀请人数」totalInvited（活动 invited 和 + referral.total 混合口径） | 新表 `referral_campaign`（V47）＋GET 列表端点；KPI1=count(ONGOING)，KPI2=referral.total（D3） |
| ②阶梯奖励 ladders | 3 档硬编码（1人/100元现金、3人/300元券、5人/5000积分）；View L289 展示块＋L348 配置弹层编辑 | 配置表 `referral_campaign_config.ladders` jsonb |
| ③层级奖励 levels | 2 级硬编码（1级5%、2级2%）；View L237 就地编辑 rate | 配置表 `referral_campaign_config.levels` jsonb |
| ④邀请排行 topReferrers | store computed 按 referrerName 聚合**当前页** referral.referrals（分页截断失真）；View L307 CBarChart Top5 | GET `/referral/top-referrers?limit=5`（Repository `countGroupByReferrer` B86 已有） |
| 机制配置 rewardType/validDays/script | 内存 ref（CASH/30天/默认话术）；View L297-301 script-box 展示＋弹层编辑 | 配置表列式字段 reward_type/valid_days/script |
| 审核奖励 approveReward | B86 已接真（referral.payReward → rewards grant） | 不动 |

### 1.2 侦察实证

- `referral.campaign_id` 列 B86 已预留（现恒空）——活动挂载零 ALTER。
- 网关 router.go L29 `/api/customer` 前缀已路由 customer-service:8082 → **网关零改**。
- 权限码 `referralCampaign:view`（PermissionMatrix L115/424/704）、`referralCampaign:edit`（L255/564）已播种 → **零新增权限码**。
- Flyway 全局最大 V46（marketing V46 落地页日历）→ **V47 可用**（落 customer-service）。
- 单行全局配置先例：customer-service `point_rule`（无 store_code）；jsonb 转换器先例：`StringListJsonConverter`。
- DataInitializer 播种先例全仓 40+（marketing/store/finance/txn/org/audit），customer-service 暂无，新增符合惯例。
- 奖励类型词表：后端 `chk_referral_reward_type` POINT/GRANT/COUPON/COMMISSION ↔ 前端 POINTS/COUPON/CASH，映射在 stores/referral.ts 适配层（POINTS↔POINT、COUPON↔COUPON、CASH↔GRANT）。
- marketing_cfg 遗留三列（referral_arrived_reward=200/referral_deal_reward=350/commission_rate=0.05）为历史口径，B86 未复用，本批不动。
- point_rule.referral_reward=500 为积分规则单行配置，与本批机制配置互补不冲突。

## 2. 四项定案（用户拍板 2026-09-23）

| # | 定案 | 结论 |
|---|---|---|
| D1 | 活动实体域归属 | **customer-service**：referral 同库、campaign_id 已预留、统计/排行零跨服务 |
| D2 | 奖励自动发放链路 | **v1 仅配置持久化**：四 mock 块后端化＋排行端点；奖励仍走 B86 手动登记＋审核发放；自动发放（deal 钩子按阶梯登记/二级返佣）留 v2，04 新登记行移交 |
| D3 | KPI2「累计邀请人数」口径 | **referral.total 纯真实**：去掉 mock 活动手填数字，与列表「共 N 条」自洽 |
| D4 | 活动 invited/converted 统计列 | **v1 仅实体＋种子**：活动表存 name/status/起止；聚合统计列待创建入口支持挂活动后 v2 再接（现恒空防误导） |

## 3. 数据建模（V47__referral_campaign.sql，customer-service）

### 3.1 referral_campaign（邀请活动）

| 列 | 类型 | 说明 |
|---|---|---|
| campaign_id | VARCHAR(24) PK | RC + 8 位日期 + '-' + 6 位当日序号（Referral 单号同款规则） |
| name | VARCHAR(64) NOT NULL | 活动名 |
| status | VARCHAR(16) NOT NULL | DRAFT/ONGOING/ENDED（chk 约束，前端 CAMPAIGN_STATUS_LABEL 同词表） |
| start_at | DATE NOT NULL | 开始日（前端 shDateStr 'YYYY-MM-DD'） |
| end_at | DATE NOT NULL | 结束日 |
| store_code | VARCHAR(16) NULL | 归属门店，空=全部门店（B88 排期「带门店可空」先例） |
| remark | VARCHAR(256) NULL | |
| created_by | VARCHAR(32) NULL | |
| created_at / updated_at | TIMESTAMPTZ NOT NULL | |

索引：idx_rc_status(status)、idx_rc_store(store_code)。DDL 幂等可重入（V44 先例）。

### 3.2 referral_campaign_config（邀请机制全局单行配置）

| 列 | 类型 | 说明 |
|---|---|---|
| config_id | VARCHAR(16) PK | 恒 'GLOBAL'（point_rule 单行先例） |
| reward_type | VARCHAR(16) NOT NULL DEFAULT 'CASH' | 前端词表 POINTS/COUPON/CASH（chk）；v2 自动发放时 service 层按既有映射转后端词表 |
| valid_days | INTEGER NOT NULL DEFAULT 30 | 有效期天数；referral.valid_days 默认 30 已对齐，v2 建单默认取此值 |
| script | TEXT NOT NULL DEFAULT '' | 邀请话术（前端提交前已过敏感词校验） |
| ladders | JSONB NOT NULL DEFAULT '[]' | [{threshold,type,amount,desc}]，type 用前端词表 |
| levels | JSONB NOT NULL DEFAULT '[]' | [{level,rate,desc}]，rate 0~1 |
| updated_by | VARCHAR(32) NULL | |
| created_at / updated_at | TIMESTAMPTZ NOT NULL | |

## 4. API 契约（/api/customer/referral 下，网关零改）

| 端点 | 权限 | 说明 |
|---|---|---|
| GET `/api/customer/referral/campaigns` | referralCampaign:view | 活动全量列表（v1 无分页，数据量小）；返回 campaignId/name/status/startAt/endAt/storeCode |
| GET `/api/customer/referral/campaign-config` | referralCampaign:view | 全局配置单行；无行时返回默认（CASH/30/''/[]/[]） |
| PUT `/api/customer/referral/campaign-config` | referralCampaign:edit | 全量 upsert（saveConfig 与层级就地编辑统一走整体提交）；validDays clamp ≥1、rate clamp 0~1 服务端复核 |
| GET `/api/customer/referral/top-referrers?limit=5` | referralCampaign:view | 按 referrer 聚合 count group by（`countGroupByReferrer` 已有），返回 [{referrerCustomerId,name,total,deal}]，deal=status=DEAL 计数；name 富化走 RefNameResolver 同款 |

金额口径：ladders.amount 为「元」（前端活规格，配置不涉资金流水，不入 cents 口径；v2 自动发放落 reward 时 ×100 转 amountCents，适配层同款换算）。

## 5. 前端改造（铁律 -1-B：View template/style 零改动）

- `api/referral.ts` 追加 4 函数：fetchReferralCampaigns / fetchReferralCampaignConfig / putReferralCampaignConfig / fetchTopReferrers（referral 域同文件内聚）。
- `stores/m5ReferralCampaign.ts` 重写切真，**对外导出签名全保留**（seed/campaigns/rewardType/validDays/script/ladders/levels/五个 KPI/topReferrers/levelReward/updateLevelRate/updateLadder/saveConfig/approveReward/三个字词典/seed）：
  - seed() 保留函数名，内部改为真实拉取（campaigns＋config＋top-referrers＋referral.seed()）；
  - KPI：ongoingCount=真实活动 ONGOING 计数；totalInvited=referral.total（D3）；convertedCount/pendingReward* 不变；
  - topReferrers 改为端点返回（ref 存储，不再是 computed 聚合当前页）；
  - saveConfig/updateLevelRate → PUT campaign-config 全量提交后刷新；乐观更新＋失败回滚提示（B86 卡3 同款）；
  - campaigns 六项种子删除，CAMPAIGN_STATUS_LABEL/PILL 词典保留。
- View 零改动验证点：L28 seed()、L40-47 KPI、L76 rankItems、L88-96 openConfig、L107 saveConfig、L127/130 层级编辑、L241/291/299-300 展示绑定全部签名不变。

## 6. 施工卡拆分

| 卡 | 内容 | 验收 |
|---|---|---|
| 卡1（后端） | V47 双表＋ReferralCampaign/Config 实体＋Repository＋Service＋Controller 4 端点＋ReferralCampaignDataInitializer（1 个 ONGOING 种子活动＋GLOBAL 配置行灌默认 ladders/levels/script）＋排行聚合 | mvn package＋docker up --build customer-service；curl 实证 4 端点 200＋种子行落库；feat commit+push |
| 卡2（前端） | api/referral.ts 追加 4 函数＋m5ReferralCampaign store 重写切真 | vue-tsc 0 error＋vitest 全绿＋vite build；浏览器 OCR 实证四块数字真实（KPI 与列表自洽、排行非空、配置弹层回显、保存后刷新一致）；feat commit+push |

## 7. 批末移交（v2 登记 04 backlog）

- 奖励自动发放链路：deal() 钩子按阶梯配置自动登记奖励（idem_key 复用 {referralId}:{triggerEvent}:{rewardType}），可选二级返佣（追溯推荐人的推荐人按 levels 费率）。
- 活动统计列：创建 referral 入口支持挂 campaign_id 后，活动列表接 invited/converted 聚合。
- 活动 CRUD UI：当前无列表/新建界面，仅 KPI 数字来源；如需运营自建活动另立项。
- config.valid_days → 建单默认值接线（v1 referral 仍默认 30 硬编码，与种子配置一致）。

## 8. 验收标准（批末）

1. 四 mock 块数字全部来自真实表/端点，m5ReferralCampaign store 无硬编码种子。
2. View template/style 零 diff（git 实证）。
3. 排行 Top5 为全库聚合而非当前页。
4. 配置保存后刷新页面回显一致（持久化实证）。
5. 纵深批数字不变，04 L157 勾销，哨兵 DORMANT，docs 原子提交。
