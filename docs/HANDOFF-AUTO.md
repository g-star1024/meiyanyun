# HANDOFF-AUTO · 自动续跑接力哨兵（铁律 11）

> 本文件是美研云夜间自主开发队列的**唯一活交接**。定时续跑任务与任何新会话都以本文件为准。
> 状态机：`ACTIVE`（可接力推进）/ `DONE`（队列全闭合，空跑退出）/ `PAUSED`（用户喊停，不得推进）。
> 每闭合一小步立即更新「最近心跳」与「下一步动作」；遇 429 / 模型上限 / 进程中断，在报错当刻刷新本文件落盘。

<!-- MACHINE:STATUS=ACTIVE -->
<!-- MACHINE:HEARTBEAT=2026-09-14 02:45 CST -->
<!-- MACHINE:BATCH=P5-B47 -->
<!-- MACHINE:CARD=卡8 隐私合规 /ai/privacy A1PrivacyView (A1-10，A1 最后一个 mock 视图)·步骤1 待开工 -->

- **状态**：ACTIVE
- **批次 / ROADMAP 落点**：P5-B47 卡8，A1 AI 中心最后一个 mock 视图 **隐私合规页 `/ai/privacy`（A1PrivacyView，A1-10）**；闭合后落账 93→**94/166**（约57%）、⬜71→**70**、域⑧ 19✅2🔧46⬜→**20✅2🔧45⬜**、A1 **15/15 页真实闭环且实测 nav mock 视图 1→0（A1 全去 mock）**。
- **仓库 / 分支 / HEAD**：`/Users/huluobo/WorkBuddy/2026-08-15-23-51-02/meiyun-platform`，分支 `main`；卡7 feat=`8ac9372`（AI 知识库，01:15 并发实例推送），卡7 docs 提交随本次 02:45 接力落盘（交付文档 DELIVERY-P5-B47-7 + 索引 + 5 分册回写 + 本哨兵改写卡8，同一 `git add -f` docs commit）。
- **最近心跳**：2026-09-14 02:45 CST（02:33 实例已完成卡7 交付文档落盘；本 02:45 接力实例完成 5 分册回写与通读勾稽——01 七处数字/口径全改、旧值零残留，00/02/03/04 一致，并在 04 表尾补登 KB-DOC-7「审计静默丢失监测」观察项 Backlog——同一 docs commit 提交推送，随后把哨兵改写为本卡8）。
- **上轮心跳**：2026-09-14 02:33 CST（02:33 实例接管卡7 步骤8，落盘交付文档 `docs/DELIVERY-P5-B47-7-2026-09-13.md` 135 行）。
- **中断类型**：无。

## 卡7 闭合存档（勿重复，2026-09-14）
- feat `8ac9372 feat(ai): B47 卡7 AI知识库整页去 mock，接真实 API 三轨真验通过`（9 文件 +1161 行：V28 两表 + domain×2 + Repository×2 + KnowledgeService 365 行 + KnowledgeController 十端点 + ai.ts 5 interface/10 函数 + A1KnowledgeView.vue 489 行）。
- 三轨真验全绿（curl 十端点矩阵 + PG V28/两表/审计 525–544 共 20 行哈希链连续 + Chrome 有数据/空态 console 零报错）；真验修复 5 项（recall nativeQuery 5 处 cast、非法 category 400、列表列 key 错配、feedbackCitation 返回类型、fat-jar 陈旧）。
- docs：DELIVERY-P5-B47-7（135 行）+ 5 分册回写，落账 92→93/166（约56%）、⬜72→71、域⑧ 18→19✅/47→46⬜、A1 14/15→15/15、实测 mock 视图 2→1。
- 观察项（已登记 04-backlog 表尾）：审计 544 REINDEX 对应的 KB-DOC-7 无对应 CREATE 审计行，疑 RestAuditRecorder 远程偶发静默丢失，留平台治理批做审计写失败补偿/对账监测。
- 两业务表验证后清场 0 行（沿用清场惯例），审计 append-only 留存；飞书 token E011 有效期至 06:13:51。

## 卡8 开工前已知事实（接力会话先核实再动手，勿臆造）
- 前端占位：`frontend/src/views/A1PrivacyView.vue`（现存 mock KPI，类名 `.a1-privacy__kpis`）；路由 `frontend/src/router/index.ts:205`、导航 `frontend/src/.../nav.ts:330` 已挂 `/ai/privacy`；权限点 `aiPrivacy:view` 已在 RBAC（`t1Rbac.ts:211`）与后端权限常量（`auth.ts:60`）就位。
- 本卡是**管理/治理面**（数据隐私/合规：如敏感数据访问审计、脱敏规则、数据导出/删除请求、授权同意管理等之一或组合），很可能与卡1 敏感词命中表 `ai_sensitive_hit`、统一审计服务相关；**具体领域边界与数据模型必须先读 A1PrivacyView.vue 现有 mock 与 nav/路由 meta 推断页面承诺，再按铁律 1 三处一致设计，不得凭空造实体**。
- 若页面所需能力依赖尚不存在的跨服务数据（如全站个人数据请求工单流）或需用户拍板合规口径，按用户指令第 7 步：哨兵记录阻塞原因保持 ACTIVE（注明等待用户），不擅自造数/不伪造闭环。

## 下一步动作（严格按序，接力会话照做）
1. **读现状**：通读 `A1PrivacyView.vue` 全文（mock 了哪些 KPI/区块/动作）、路由 meta 与 nav 文案、权限矩阵 `aiPrivacy:view` 三角色归属；对照 DELIVERY-P5-B47 系列与 02-modules 域⑧ 口径，确定本页真实数据从哪些既有表/服务来（敏感词命中、审计、模型/功能治理等），列出「可直接接真」与「需新建端点/表」两类。
2. **设计（铁律 1 三处一致）**：如需新表走共享 Flyway 链下一个版本号（当前 AI 侧到 **V28**，新表为 V29，DDL-only 无 seed、CHECK/索引/updated_at 触发器齐）；后端 ai-service 加 Controller/Service/Repository，类级 `/api/ai/privacy`+`@RequirePerm("aiPrivacy:view")`，写动作另挂 edit 权限并全动作审计；管理检索面不接 FeatureCatalog、不走模型绑定配额计费（与卡7 同口径），除非页面确有 AI 出站。
3. **后端复编 + 前端接线（铁律 5 真实 API）**：`mvn -pl ai-service -am package -DskipTests`（务必 package 非仅 compile，避免 fat-jar 陈旧）；`frontend/src/api/ai.ts` 补 privacy 接口组，A1PrivacyView.vue 整页去 mock，样式主体零改动、差异收敛在 script 适配层；空态/无权限/远程失败诚实分层。
4. **build（铁律 6）**：`npm run build`（含 vue-tsc）exit 0；compose 重建 ai-service + frontend 静态镜像，确认 V29（若有）applied。
5. **三轨真验（铁律 7）**：curl 经网关端点矩阵（401/400/403/404/成功+写动作幂等/翻转才审计）；PG 查新表/触发器/审计哈希链连续/payload 合法 jsonb；Chrome 验 KPI/区块/动作/空态、console 零 error/warn；token 在 `/tmp/meiyun_token.txt`（E011 冯区域，exp 2026-09-14 06:13:51，过期重新登录）。业务表验证后按惯例清场、审计留存。
6. **feat 提交（铁律 8，一卡一 feat）**：代码单卡 `feat(ai): B47 卡8 隐私合规页整页去 mock，接真实 API 三轨真验通过` + push origin main。
7. **docs 提交（铁律 9/10）**：写 `docs/DELIVERY-P5-B47-8-2026-09-14.md`；回写 5 分册（00 顶部加卡8简报、01 数字 94/166 等、02 加隐私合规行、03 表底加卡8时间线、04 的 A1 汇总行去掉隐私合规并把 mock 轨迹收尾为 0）并通读勾稽；`git add -f docs/DEVELOPMENT-ROADMAP.md docs/roadmap/*.md docs/DELIVERY-P5-B47-8-*.md docs/HANDOFF-AUTO.md` 同一 docs commit + push。
8. **铁律 9 中文汇报**：含「整体完成度 94/166（约57%），本批新增 1 个；A1 AI 中心 15/15 页全部去 mock、实测 mock 视图清零」。
9. 卡8 闭合即 **P5-B47 整批与本自主队列全部闭合**：把本哨兵 `MACHINE:STATUS` 置 `DONE`（不再改写新卡片），心跳更新为闭合时刻。

## 续跑边界
- 接力会话若发现本哨兵 `心跳` 距今 <15 分钟，视为可能有会话仍在跑，只做只读检查不写代码，避免并发踩车；≥15 分钟无心跳且状态 ACTIVE 才接管。
- 撞 429 / 模型上限：刷新本文件心跳与「中断类型」后退出，等下一周期（每小时）重试，不跳验证/提交/回写。
- 用户说停：置 PAUSED；队列全闭合：置 DONE。
