# 美研云门店中台 · 自动开发接力哨兵（HANDOFF-AUTO）

> 用途：铁律 11 自主续跑的唯一事实源。任何接续会话/定时任务先读本文件，再按「开发前 checklist」执行。
> 维护规则：每批开工改 ACTIVE+心跳；批末（或中断前）改 DORMANT+存档。心跳格式 `YYYY-MM-DD HH:mm CST`。

<!-- MACHINE:STATUS=ACTIVE -->
<!-- MACHINE:HEARTBEAT=2026-09-15 22:25 CST -->
<!-- MACHINE:BATCH=P5-B51 M1 调度 Backlog 纵深缺口批（04-backlog L140-145 六缺口，用户 2026-09-15 晚拍板「P5-B51开工吧」=此前顺序②授权落地；多依赖跨域新数据源，先逐卡只读侦察数据源就绪度，无源继续诚实空态） -->
<!-- MACHINE:CARD=卡6 L142 appointment 加 sku_code 列（用户四项拍板已全部落定：①施工序=按建议序 L143→L140→L145；②L140 范围=同时放开 DEVICE 派单写（读+写全闭环，设备参与时段占用与 409 冲突校验）；③L142 方向=appointment 加 sku_code 列+durationMin 从 SKU 真源取；④无源缺口=都保留诚实空态（L141 URGENT/L144 班次表）。卡5 L145 改期联动已 22:25 全闭合：feat 50a7eb7 已 push，三轨真验全绿——跟随移动/409 冲突回滚/422 班次越界/无派单回归/PG 双轨 audit） -->

## 当前状态（人读区）

- **批次**：P5-B51 M1 调度 Backlog 纵深缺口批 **2026-09-15 20:15 开工**（用户拍板「P5-B51开工吧」）；哨兵 ACTIVE
- **用户四项拍板（持续指导本批施工）**：①施工序=按建议序（L143 DONE→L140 DEVICE→L145 改期）；②L140 同时放开 DEVICE 派单写（读+写全闭环）；③L142 appointment 加 sku_code 列；④L141/L144 都保留诚实空态
- **阶段**：卡3 L143 DONE 完成态联动 **21:30 全闭合**（8 文件+新建 DispatchCompletion，feat `79a8ae4`+顺手 fix `2714287` 均已 push；三轨真验 8 项全绿）→ **卡4 L140 DEVICE 接真+放开派单写 待开工**
- **范围（04-backlog L140-145，B49 卡12 登记的 M1 调度六缺口）**：L140 DEVICE 设备档案（GET /resources 三源之一现诚实空态）/ L141 URGENT 加急源（jobs 加急标记无源，前端已删 stats.urgent 死代码）/ L142 durationMin 真实时长（现固定 end=start+60min，SKU↔project 名匹配率 0%）/ L143 assignment DONE 完成态（现仅 SCHEDULED/IN_PROGRESS/RELEASED）/ L144 医生房间班次表（现固定 09:00-20:00）/ L145 派单时段自由度改期（start 现锚 apptTime）。**多依赖跨域新数据源：开工逐卡只读侦察数据源就绪度，有源才施工、无源继续诚实空态**；白天先建议后拍板。
- **保留登记（不在 B51 范围，随后续批次评估）**：L133 impersonate＝大（JWT act/realSub claim+meiyun-security 全服务回归+前端换 token，与 L46 合并）、L123 报告哈希验真 UI＝中（content_hash+规范化字节口径，DSAR/consent 远期）。非本批：L89 setup-seed-db 保留 audit_log、L128 ai-service seed Flyway V17-V29 悬置。
- **记账口径**：纵深缺口批以「缺口闭合即勾 04-backlog 行」为记账单位——完成度数字（✅108/166≈65%、⬜56、🔧1、域⑧ 34✅2🔧31⬜）仅当缺口对应功能真实落地才动；侦察无源的行保持登记不动、诚实说明。
- **上一批**：P5-B50 平台治理/还债批九卡全闭合（B49 四项观察项清零，见文末存档）

## 开工基线

- 代码 HEAD=`50a7eb7`（B51 卡5 feat）；总 HEAD=`50a7eb7`，均已 push origin/main
- 后端 19 服务 + 网关在线（卡1 开工用 `bash /tmp/meiyun-health.sh` 复核）；正式前端 http://localhost:8080 / 网关 8443；seed 前端 127.0.0.1:18080 / 网关 18443
- 登录：curl 通道 POST `https://127.0.0.1:8443/api/org/auth/login`（curl -k，E011/meiyun123=REGION_MGR 华东域，token 存 /tmp/meiyun_token.txt，验证前重新登录）；Chrome 通道 http://localhost:8080 手工填表（快捷登录已关闭）
- 铁律 10 开工读数：索引+00～04 五分册本批开工前已全部整读
- docs 提交坑：`docs/` 被 .gitignore 整体忽略；已跟踪文档用 `git add -u docs/`，仅新增 docs 文件用 `git add -f`；Grep 工具对 docs 超长中文行失效，用 shell `grep -rn` 或 Read

## 下一步动作（B51 卡序列）

0. ✅ 卡0 哨兵激活（本 docs 提交）：铁律 10 全读 6/6（索引→01→02→04→03→00）→ STATUS=ACTIVE、心跳 20:15、BATCH=P5-B51、卡序列落盘、基线 HEAD 更新 b7cec03
1. ✅ 卡1 六缺口数据源只读侦察（20:25 全闭合，铁律 -1-A 链路先行，零代码改动）：
   - ✅ L140 DEVICE **数据源就绪可施工**：B13 equipment 域完整（equipment 表 store_code+asset_no 唯一、category 六类、status 四态 NORMAL/CALIBRATING/REPAIRING/DISABLED、金额分、下次校准/维保日；公开端点 /api/stores/equipments 挂 equipment:view/edit+DataScope）；seed 实证 SST01 8 设备（5N/1C/1R/1D）+11 维保、prod 0/0；**缺 InternalEquipmentController（复刻 InternalRoomController 范式）+txn 侧 StoreEquipmentClient（复刻 StoreRoomClient 软降级）+DispatchService DEVICE 分支**（现 dispatch() 拒绝 DEVICE 写，是否放开待拍板）
   - ✅ L141 URGENT **无源**：appointment 无 priority/urgent 列（CHECK 仅 source/status）→ 继续诚实空态，或拍板加列（schema 变更+预约 UI 加急开关）
   - ✅ L142 durationMin **半就绪需拍板**：product_sku.duration_min integer not null 真实存在（90/70/60/45/40/30/15 分钟档），但 appointment.project 12 个中文项目名 vs SKU 品牌商品名 **字面匹配≈0%、无关联键**；选项 (a) appointment 加 sku_code 列 (b) project→SKU 人工映射表 (c) 保持固定 60min 空态；seed appointment 共 166 行
   - ✅ L143 DONE **数据源完全就绪可施工**：联动链 dispatch_assignment.appt_no→arrival.appt_no→consult_plan.arrival_id→ConsultPlanService.treatDone（L716-766：TREATING→DONE+EM 治疗记录+revision TREAT_DONE+audit+AFTER_COMMIT FollowupScheduler 先例）**全在 txn-service 同服务同库**，事务内直接联动零跨服务；备选语义 ArrivalService.done（接诊完成，语义偏接诊）
   - ✅ L144 班次表 **无源**：库内仅 ai_scheduling_plan/ai_scheduling_slot（B47 卡6 证实 AI 草稿非现排），无 shift/duty/roster 表 → 继续固定 09:00-20:00 诚实空态
   - ✅ L145 派单改期 **半就绪**：AppointmentController.reschedule（L101-116，仅已预约态+HH:mm 校验+audit RESCHEDULE）真实存在，**但不联动 dispatch_assignment——改期后 SCHEDULED 派单滞留旧时段**；可施工小项=reschedule 同事务联动释放/跟随 assignment（同服务同库）；自由时段派单为产品决策需拍板
2. ✅ 卡2 侦察汇报+四项拍板已落（21:00 用户 AskUserQuestion 拍板：①按建议序开工 ②同时放开 DEVICE 派单写 ③appointment 加 sku_code 列 ④L141/L144 都保留诚实空态）
3. ✅ 卡3 L143 assignment DONE 完成态联动（**21:30 全闭合**：9 文件 +150/-25 含新建 DispatchCompletion；feat `79a8ae4`+顺手修既有 bug fix `2714287` 均已 push；三轨真验 8 项全绿——PG done_at 列置值/REQUIRES_NEW 日志「置DONE 1条」/resources DONE 回显/jobs 不重现/release 422/再派 422/DONE 不占时段 id=7 同医生同时段派单成功/audit DISPATCH/6/DONE actor=SE004）
4. ✅ 卡4 L140 DEVICE 接真+放开 DEVICE 派单写（**22:05 全闭合**：8 文件 +179/-21 含新建 InternalEquipmentController+StoreEquipmentClient；feat `86d4a65` 已 push；三轨真验全绿——读侧 resources?type=DEVICE 返回 5 台 NORMAL（校准/停用/维修 3 台过滤）、写侧派 EQ-L001 成功 id=8 SCHEDULED、同设备同时段 409「皮秒激光治疗仪 在 14:00-15:00 已有排单」、派校准中 EQ-R002 404「所选设备不存在或不可用（仅正常状态设备可派单）」、resources 占用块回显 14:00-15:00 唐玉兰、PG dispatch_assignment DEVICE 行+audit_log DISPATCH payload resourceType=DEVICE 双落库；设计期规避两坑——EquipmentService.toView 维保记录 N+1 改轻量 listDispatchBriefs、前端 adaptAssignment 二元映射会把 DEVICE 错映射 DOCTOR 改显式三态）
5. ✅ 卡5 L145 预约改期联动（**22:25 全闭合**：2 文件 +56/-3；feat `50a7eb7` 已 push；三轨真验全绿——改期 000007→16:00 派单 id=8 跟随 16:00-17:00 resources 回显/改期 16:30 与既有排单重叠 409「皮秒激光治疗仪 在新时段 16:30-17:30 与已有排单 16:00-17:00 冲突」且预约回滚仍 14:30/改期 19:30 越班次 422/改期 17:30 成功跟随 EQ-L001 双块/无派单预约改期回归 200/PG 两行派单 bizDate+start/end 跟随正确+audit 双轨 RESCHEDULE×3+RESCHEDULE_FOLLOW×2（拒绝两次无 FOLLOW 记录））
6. 🔄 卡6 L142 appointment 加 sku_code 列（下一卡，拍板已授权：schema 变更+预约创建选 SKU+durationMin 从 SKU duration_min 真源取，替代固定 60min；seed appointment 共 166 行存量）
7. ⬜ 批末：DELIVERY-P5-B51-2026-09-15.md + 五分册回写（00 顶部简报/03 表底 P5-B51 行/04 已闭合行勾销/02 挂载备注/01 数字仅真实落地才动）+ 同批原子 docs 提交 + 铁律 9 汇报 + 哨兵 DORMANT/B52

## 中断恢复指引

- 心跳 <15 分钟：另一实例活跃，**只读不接管**，直接退出
- 心跳 ≥15 分钟且 STATUS=ACTIVE：前实例中断，从「下一步动作」第一个 ⬜ 续跑
- 429/模型上限：刷新本文件心跳后退出，等每小时定时任务重试
- 每卡开工前必须重读本文件 + 铁律 10 全读五分册

## P5-B50 闭合存档（2026-09-15）

- **主题**：平台治理/纯还债批（用户拍板顺序①），卡1 只读侦察+卡2–卡8 共九卡全闭合（8 个 feat commit+卡8b 零代码）；B49 登记四项观察项全部清零
- **完成度**：纯还债批数字一律不变——**✅108/166=约65%、🔧1、⬜56 约34%；域⑧ 34✅ 2🔧 31⬜**
- 卡2 采购列表越权 `da3d812`（3 files +116/-14，DataScope 基座+越权用例，seed E011 6→4/SST03 消失）；卡3 大屏时区 `cbd6692`（2 files +9/-2，shTimeStr 锚 Asia/Shanghai，05:26Z→13:26）
- 卡4 target 行级 DataScope `3a263ab`（6 files +206/-5，canReadTarget 四角真验 7/5/5/10）；卡5 登录四门控 `7f08103`（4 files +52/-23，auth/storeContext/CShellDesktop/notification，无 token 零请求）
- 卡6 哈希链全量断链清单 `aecf97f`（5 files +201/-8，prod breaks=[380,520] 均 B48 修复前历史窗口、seed []/117、卡8c 后 total=124）；卡7 网关真实客户端 IP `5251306`（7 files +364/-7，XFF→X-Real-IP→remoteAddr→unknown，20 单测，合规链路去硬编码 "web"）
- 卡8a 耗材越权+库存 watch `dd4307a`（5 files +179/-39，两 Repository 删 cast null JPQL，seed 档案 36→18/流水 38→18，InventoryView 切店重拉）；卡8b 零代码（SE101 周岚双库 SUPER_ADMIN/scope=GROUP/stores=[]/perms=* 即合格，不新增 SE106；三口径对账差额恰为越界 SST03 ¥999,000,000）；卡8c actor 按通道强制 `43f009b`（1 file +9/-2，五通道真验，P1 伪造 HACK→SE101）
- 批末回写：DELIVERY-P5-B50-2026-09-15.md（14 章 170 行）+五分册（00 顶部简报/03 表底全行/04 九行勾销 L122/L124/L127/L129/L132/L134/L146/L147/L148/02 十处挂载/01 L18+L36 尾接数字不动），同批原子 docs 提交
- **保留登记不动工**：04 L133 impersonate（大，act/realSub+全服务回归+前端换 token，与 L46 合并）、L123 报告哈希验真 UI（中，content_hash+规范化字节口径；DSAR/consent 远期）
- **append-only 留档（不可删）**：prod meiyun_core.compliance_check 手插 id=1 PRIVACY「L134 平台治理验真临时项」现 FAIL；prod 审计 id588/589（卡7）、id590（卡8c P1 伪造）；seed 审计 id180-182（卡7 curl）、id183（Chrome 冯区域 ip=192.168.65.1）、id184/185/186（B50_8C_TEST T1/T2/T4）
- **下一批 P5-B51**：M1 调度六缺口（04 L140-145，用户已拍板顺序②）——DEVICE 设备档案/URGENT 加急源/durationMin 真实时长（现 SKU↔project 名匹配率 0%）/assignment DONE 完成态/医生房间班次表（现固定 09:00-20:00）/派单时段自由度改期（start 现锚 apptTime）；多依赖跨域新数据源，开工逐卡只读侦察，无源继续诚实空态

## P5-B49 闭合存档（2026-09-15）

- **主题**：M1 集团管控屏接真批（C 方向），卡0 落账纠错+卡1 侦察+卡2–卡12 共 12 卡全闭合；M1 集团屏 **17/17 全切真**（3 真实页+14 已切真页+0 mock，M1 批目标达成）
- **完成度**：批初 94/166=约57% → 批末 **✅108/166=约65%、⬜56、🔧1**；域⑧ 平台与基建 34✅ 2🔧 31⬜
- 卡2 审计日志 `43f52ca`（total=553 三轨对账；orphan 只 warn 不删、两轮恒=100 共享索引拓扑；卡0 已纠 f2181aa 失实）
- 卡3 区域+门店主数据 `494c272`（6 前端文件 +248/-653）；卡4 集团聚合三页 `f672a80`（跨店例外域 -1-D GET /finance/group-overview，8 文件 +407/-154）
- 卡5 采购补全 `a108203`（procurement 四表六态状态机/审批阈值/移动均价，15 文件 +1425/-80）；卡6 SOP `d4c6453`（sop 三表八端点，11 文件 +1283/-10）
- 卡7 数据大屏 `815f006`（screen 包 overview+SSE，修复 SSE 认证串扰+nginx 缓冲双缺陷，13 文件 +848/-79）
- 卡8 目标管理 `1f432a2`（biz_target 五指标×三级×四态+idemKey 幂等，8 文件 +820/-54）；卡9 合规中心 `b7baee5`（compliance 域+V31+RECHECK 八键）
- 卡10 健康度巡检 `177a32e`（health 三表、clamp[40,98] 扣分服务端化，12 文件 +808/-90）；卡11 报表中心 `01c7af1`（report 域 BYTEA CSV 异步、R01/R02 真实聚合，13 文件 +1241/-162）
- 卡12 调度中心 `0b3dfb0`（收官，10 文件 +964/-99 跨 txn/store/org 三服务：dispatch_assignment ddl-auto、四端点 resources/jobs/dispatch/release、改派 422/重叠 409/重复 409、权限预置零改动、前端删 urgentJobs 死代码；三轨真验四角全绿）
- 批末回写：DELIVERY-P5-B49-2026-09-14.md 卡12 设计章+交付章（L461-537）+五分册（history/dashboard/modules/timeline/backlog）docs `05f1593`（6 文件 79+/10-，17/17 收官口径全册统一，历史卡 1/17…13/17 记录保留）
- **Backlog 净增**：卡12 新登记 6 纵深缺口（DEVICE 设备档案域/URGENT 加急源/durationMin 真实时长/DONE 完成态/班次表/派单时段自由度，建议远期 M1）+1 观察项（登录页 CShellDesktop 全局壳无 token 四路 401 console 噪音，既有现象非卡12 引入，建议平台治理批修复=壳按 token 门控预拉取）；另历史卡累计 backlog 见 04-backlog
- **下一批 P5-B50 候选方向（待用户拍板）**：①平台治理批（登录页 401 观察项+卡2 历史断链 #380 全量断链清单增强+各卡登记的平台类小项）；②M1 Backlog 纵深缺口（DEVICE/URGENT/durationMin 等，业务纵深但多数依赖跨域新数据源）；③其他用户指定方向

## P5-B48 闭合存档（2026-09-14）

- 卡1 审计写失败补偿/对账监测 feat `4bfb658`（14 文件：outbox+中继+对账监测三道防线，KB-DOC-7 幻影审计观察项复核登记，历史断链 id=380 append-only 不可篡改存量保留）
- 卡2 ES reindex 对账治理 feat `9c1fd6a`（4 文件：文档级 diff 三清单——missing 逐条 upsert 自动补齐、orphan 仅报告+warn 待人工不自动删（两轮恒=100，SC* seed/app 共享索引拓扑预期）+6h 定时巡检；M001–M003 首跑补齐 es110→113、二跑幂等；原「ES110 vs PG100 漂移 10」系 seed-only 口径误判，权威源 seed∪core=113）
- 卡3 marketing ddl-auto update→validate feat `188c90b`（1 文件：18 表零 drift，现网+scratch 全新库双场景一次通过）
- 卡4 推理模型 max_tokens 遵从度+前端日期统一锚定 Asia/Shanghai feat `14cdd8f`（40 文件 +146/-70：finish_reason=length WARN/空回答异常双分支，lenient ping 实证 128>8 仍 SUCCESS；时区实际 56 处 slice，登记 21 处系低估本批更正）
- 纯还债批：完成度 **94/166=约57%、🔧2、⬜70 均不变**；勾销 04-backlog 四行（L121 审计静默监测、L76 尾 ES 对账、L97 ddl-auto validate、L117 中段时区推广）
- 交付文档：DELIVERY-P5-B48-2026-09-14.md

## P5-B47 闭合存档（2026-09-14）

- 卡8 AI 知识库检索闭环 feat `b26d52e`（检索事件处置台+zhparser/pg_trgm 分词+附件版本审批流）
- 完成度 94/166=约57%、🔧2、⬜70
- 交付文档：DELIVERY-P5-B47-8-2026-09-14.md
