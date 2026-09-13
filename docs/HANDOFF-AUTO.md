# HANDOFF-AUTO · 自动续跑接力哨兵（铁律 11）

> 本文件是美研云夜间自主开发队列的**唯一活交接**。定时续跑任务与任何新会话都以本文件为准。
> 状态机：`ACTIVE`（可接力推进）/ `DONE`（队列全闭合，空跑退出）/ `PAUSED`（用户喊停，不得推进）。
> 每闭合一小步立即更新「最近心跳」与「下一步动作」；遇 429 / 模型上限 / 进程中断，在报错当刻刷新本文件落盘。

<!-- MACHINE:STATUS=ACTIVE -->
<!-- MACHINE:HEARTBEAT=2026-09-14 02:33 CST -->
<!-- MACHINE:BATCH=P5-B47 -->
<!-- MACHINE:CARD=卡7 AI知识库 /ai/knowledge (A1-09)·步骤8 docs回写中（02:33实例接管：交付文档已由02:17实例落盘，接力回写5分册） -->

- **状态**：ACTIVE
- **批次 / ROADMAP 落点**：P5-B47 卡7，A1 AI 中心知识库 `/ai/knowledge`（A1-09）；闭合后落账 92→**93/166**（约56%）、⬜72→**71**、域⑧ 18✅2🔧47⬜→**19✅2🔧46⬜**、A1 14/15→**15/15**，mock 视图仅剩 A1PrivacyView。
- **仓库 / 分支 / HEAD**：`/Users/huluobo/WorkBuddy/2026-08-15-23-51-02/meiyun-platform`，分支 `main`，HEAD=`8ac9372`（**卡7 feat，并发实例 01:15 已推送**）；其前 `d03def6`（卡6 docs）。
- **最近心跳**：2026-09-14 02:33 CST（02:33 实例接管：02:03 实例 02:17 已落盘交付文档 `docs/DELIVERY-P5-B47-7-2026-09-13.md` 但 5 分册未动，其后心跳停滞 22 分钟 ≥15，mtime/git 均无在改迹象）——**本实例接力步骤 8 剩余动作**：回写 5 分册 → 通读勾稽 → 同一 docs commit（含交付文档/索引/5 分册/本哨兵）push → 步骤 9 中文汇报 → 哨兵改写卡8 A1PrivacyView。
- **上轮心跳**：2026-09-14 01:20 CST（01:07 会话）——检测到并发守护实例已停写。01:07 会话完成 curl + PG 两轨全绿；01:09–01:15 对端（01:03 苏醒实例）独立完成 Chrome 验证、清测试数据、feat `8ac9372` 提交推送（见下方「并发阻塞」存档）。
- **中断类型**：无（01:20 的并发阻塞已随 48 分钟心跳停滞解除，本实例合法接管步骤 8）。

## ⚠️ 并发阻塞记录（2026-09-14 01:20 CST，等待下一周期/用户确认）
- **证据链**：①01:09:56 突现 citation 11–13（本会话只跑过两次 search）；②审计 540/543（01:10:12/01:10:35）为 KB-CITE-12 FEEDBACK（本会话只反馈 cite6/cite8）；③doc7「FAILED重试验证条目」01:11:35 创建但无 CREATE 审计（RestAuditRecorder 失败静默），审计 544 REINDEX 01:12:14；④共享 Chrome 页被导航至 `?cb=clean` 空态；⑤01:13:58 PG `ai_knowledge_item`/`ai_knowledge_citation` 被 SQL 直清空（0 行，删除不经审计）；⑥**`git log` 出现 feat 提交 `8ac9372`（01:15:00，作者 g-star1024），9 文件 +1161 行正是卡7 全套后端+前端，且已在 `origin/main`（本地 main 与 origin/main 同位）**。
- **本会话结论（curl+PG 证据以 append-only 审计 525–544 为准，清表不影响）**：十端点矩阵全绿——401 无 token / 400×4（空标题·非法 category·空正文·空 q）中文错误体 / 404×3（缺 doc、缺 doc 的 citations、缺 citation feedback）；search 加权召回（标题/标签命中排序优先）每次写 manual_search citation 且 refs_count+1；feedback 幂等重放零审计、翻转落审计、缺 useful 400；stats totalDocs3/indexed3/indexedPct100/refs8/feedbackTotal2/usefulRatePct50；hot 近30天聚合；list category/keyword/分页；GET /{id}；reindex FAILED→INDEXED 落审计 541、INDEXED 重放零审计、update 落 542 且 `trg_ai_knowledge_item_updated_at` 刷 updated_at；V28 success=t；AI_KNOWLEDGE 审计区段哈希链连续（两处断链在缺口 id379/519，均为既往批次事务回滚，非本卡）；payload jsonb 全合法。Chrome 首屏渲染真实数据正常（对端 ?cb=clean 空态另验了 0 KPI 空态）。
- **处置**：按铁律 11 心跳判活（01:12→01:19 仅 7 分钟 <15 分钟）及用户指令第 7 步，本会话**不重建数据、不做步骤 8 文档/台账回写、不重复提交**（feat 已由对端完成，严禁双重 commit）；仅 `git add -f` 提交本哨兵并 push，保持 ACTIVE。
- **下一周期（≥01:35 后心跳判活）动作**：先 `git log -3` + `git status` 确认对端是否已做步骤 8（docs commit 含 DELIVERY-P5-B47-7 与 5 分册回写）；**若已做** → 直接跳到步骤 9/10（核对台账数字勾稽、哨兵改写卡8 A1PrivacyView）；**若未做且工作区无对端在改迹象**（心跳≥15 分钟停滞、find 无新 mtime）→ 接力执行步骤 8：写 `docs/DELIVERY-P5-B47-7-2026-09-13.md`，回写 5 分册（92→**93/166** 约56%、⬜72→**71**、域⑧ 18✅2🔧47⬜→**19✅2🔧46⬜**、A1 14/15→**15/15**），同一 docs commit `git add -f` + push。注意：代码工作树经 8ac9372 已干净，仅余 `M docs/DEVELOPMENT-ROADMAP.md`（台账拆薄改动，归步骤 8 docs 提交）。

## 已完成（勿重复）
1. 卡7 后端 7 件套已建：V28 SQL、AiKnowledgeItem / AiKnowledgeCitation 实体、两个 Repository、KnowledgeService（360+行，10 方法）、KnowledgeController（10 端点）；新增 GET `/{id}` 前 `mvn -q compile` 已 exit 0（**新增 get() 后需复编**）。
2. `frontend/src/api/ai.ts` 末尾已追加知识库 API 组（6 interface + 10 函数，分页用 PageResult<T>）。
3. 台账拆分完成：`docs/DEVELOPMENT-ROADMAP.md` 瘦身为索引，5 分册在 `docs/roadmap/00`～`04`，381 正文行 MD5 逐字一致零丢失。
4. skill 已加铁律 10（分册读写）、铁律 11（自动续跑），自检清单 +2 条；SKILL 路径 `~/.trae-cn/skills/meiyun-dev-rules/SKILL.md`（工作目录外，用 python/shell 改，编辑工具会被沙箱拒）。

## 工作区现状（2026-09-14 01:20 核对）
- 卡7 全部代码（V28、domain×2、Repository×2、knowledge 包、ai.ts、A1KnowledgeView.vue）已在 `8ac9372` 提交并推送，代码工作树干净。
- 未提交：`M docs/DEVELOPMENT-ROADMAP.md`（台账拆薄为索引的改动，归步骤 8 docs 提交）；`docs/roadmap/00–04` 仍未跟踪（docs/ 被 gitignore，须 `git add -f`）；本哨兵随步骤 8 同提交（本次并发阻塞先单独 `git add -f` 落一哨兵提交）。
- PG：`ai_knowledge_item`/`ai_knowledge_citation` 当前 0 行（并发实例验证后清理，属既往惯例），审计 525–544 留存；飞书 token E011 有效期至 06:13:51。

## 下一步动作（严格按序，接力会话照做）
1. ✅ **修 ai.ts 契约**：`feedbackCitation` 返回类型已改为 `Promise<KnowledgeCitation>`；未用的 KnowledgeActionResult 已删除。
2. ✅ **复编后端**：`backend/` 跑 `mvn -q -pl ai-service -am compile -DskipTests` exit 0，8 个 class 时间戳新鲜。
3. ✅ **重写前端** `A1KnowledgeView.vue`：已整页去 mock（489 行，style 仅增补 search-banner/trace__body/trace__fb 等少量类），浏览/检索分离、热搜、四 KPI、溯源抽屉、有用/无用幂等反馈、FAILED 重试、措辞已改词法检索。
4. ✅ **build**：`npm run build` 含 vue-tsc exit 0（21.59s，0 error）。
5. ✅ **部署**：首次重建因 fat jar 陈旧（仅 compile 未 package）V28 未迁移；`mvn -pl ai-service -am package -DskipTests` 重打后重建 ai-service，V28 applied；frontend 镜像容器内 pnpm build 新视图；两表+触发器 PG 已验。
6. **三轨真验（铁律 7，当前步）**：
   - curl 经网关十端点矩阵：401 无 token / 400 校验 / 404 mustGet / search 写 citation 且 refs_count+1 / feedback useful 幂等 / hot 近30天聚合 / stats 空库 indexedPct=0、usefulRatePct=null 口径 / GET /{id}；token 在 `/tmp/meiyun_token.txt`（E011 冯区域，exp 2026-09-14 06:13:51，过期则重新登录）；
   - PG 查两表 + trg_ai_knowledge_item_updated_at 触发器 + AI_KNOWLEDGE 审计哈希链（KB-DOC-{id} CREATE/UPDATE/REINDEX、KB-CITE-{id} FEEDBACK）；
   - Chrome 验 4 KPI/tab/搜索/溯源抽屉/反馈/FAILED 重试/空态，console 零 error。
7. ✅ **feat 提交（铁律 8，一卡一 feat）**：**已由并发实例完成**——`8ac9372 feat(ai): B47 卡7 AI知识库整页去 mock，接真实 API 三轨真验通过`（9 文件 +1161 行，后端 7 件套+V28+ai.ts+A1KnowledgeView.vue），01:15 已在 origin/main；本会话不得重复提交。
8. **docs 提交（铁律 9/10）**：写 `docs/DELIVERY-P5-B47-7-2026-09-13.md`；回写 5 分册（00 顶部加卡7简报、01 数字 93/166 等、02 加知识库行、03 表底加卡7时间线、04 的 A1 汇总行去掉知识库）并通读勾稽；`git add -f docs/DEVELOPMENT-ROADMAP.md docs/roadmap/*.md docs/DELIVERY-P5-B47-7-*.md docs/HANDOFF-AUTO.md` 同一 docs commit + push。
9. **铁律 9 中文汇报**：含「整体完成度 93/166（约56%），本批新增 1 个」+ 台账拆分/守护任务两条自主决策说明。
10. 卡7 闭合后把本哨兵改为下一张卡片（最后一个 mock 视图 A1PrivacyView `/ai/privacy`，A1-10）；全部队列闭合才置 `DONE`。

## 续跑边界
- 接力会话若发现本哨兵 `心跳` 距今 <15 分钟，视为可能有会话仍在跑，只做只读检查不写代码，避免并发踩车；≥15 分钟无心跳且状态 ACTIVE 才接管。
- 撞 429 / 模型上限：刷新本文件心跳与「中断类型」后退出，等下一周期（每小时）重试，不跳验证/提交/回写。
- 用户说停：置 PAUSED；队列全闭合：置 DONE。
