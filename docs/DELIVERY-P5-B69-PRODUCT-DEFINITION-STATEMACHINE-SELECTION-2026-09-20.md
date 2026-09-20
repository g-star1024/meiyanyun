# DELIVERY-P5-B69 B 类产品口径/状态机/选型决策＋竞品调研

> 批次：P5-B69 | 类型：B 类口径/状态机/选型决策＋竞品调研 | 日期：2026-09-20
> DESIGN：[DESIGN-P5-B69-PRODUCT-DEFINITION-STATEMACHINE-SELECTION-2026-09-20](DESIGN-P5-B69-PRODUCT-DEFINITION-STATEMACHINE-SELECTION-2026-09-20.md)（501 行，commit `8cb7d28`）

---

## §1 背景与口径

B69 属 B 类「产品口径/状态机/选型决策」批——不新增业务功能页面，而是为既有模块补齐口径定义、状态机守卫、选型底座与竞品调研结论。用户授权原文：「B. 产品口径/状态机/选型决策按照你推荐的来就可以，做好竞品调研选择最合适的就行」。

**范围**：DESIGN §4.1 三项可施工项（L133 退回修改/L132 读时聚合/L54 存储抽象），零新表/零 Flyway/零新权限码/零新页面/零新路由/零新增 CSS。

**数字锁定**：✅109/🔧1/⬜55=166（约66%）一律不动，0 新页面。

---

## §2 影响面

### 后端（finance-service，6 文件）

| 文件 | 变更 | 说明 |
|------|------|------|
| BizTargetService.java | +129/-5 | view() aggregatedRevenue 默认值＋list() computeAggregatedRevenue()＋reset() 状态守卫＋4 私有辅助方法 |
| RevenueMonthlyRepository.java | +3/-0 | findByStoreCodeInAndPeriodMonthBetween 区间查询 |
| TargetController.java | +7/-0 | POST /{targetId}/reset 端点 |
| StorageService.java | +15/-0 | 存储抽象接口（新建） |
| LocalStorageService.java | +102/-0 | 本地文件系统实现（新建） |
| application.yml | +5/-0 | storage 配置段 |

### 前端（3 文件）

| 文件 | 变更 | 说明 |
|------|------|------|
| api/target.ts | +4/-0 | resetTarget export 函数 |
| stores/m1Target.ts | +12/-2 | import resetTarget＋reset() 方法 |
| views/M1TargetView.vue | +4/-1 | REJECTED 态退回修改按钮（variant="ghost"） |

### 不涉及

- 网关零改（/api/finance 前缀整段透传）
- 零新表/零 Flyway/零新权限码
- 零新页面/零新路由/零新增 CSS
- 同服务 JVM 直调不新增 internal HTTP

---

## §3 代码 numstat 权威表

### feat commit `363bb1d`（9 files, +277/-7）

```
129     5       backend/finance-service/.../BizTargetService.java
3       0       backend/finance-service/.../RevenueMonthlyRepository.java
7       0       backend/finance-service/.../TargetController.java
102     0       backend/finance-service/.../storage/LocalStorageService.java
15      0       backend/finance-service/.../storage/StorageService.java
5       0       backend/finance-service/.../application.yml
1       0       frontend/src/api/target.ts
12      2       frontend/src/stores/m1Target.ts
3       0       frontend/src/views/M1TargetView.vue
```

### fix commit `832a1fa`（2 files, +4/-1）

```
3       0       frontend/src/api/target.ts
1       1       frontend/src/views/M1TargetView.vue
```

### 合计（11 files, +281/-8）

---

## §4 三轨真验

| 轨道 | 结果 | 详情 |
|------|------|------|
| 前端 typecheck | ✅ 0 error | vue-tsc --noEmit 通过 |
| 后端编译 | ✅ OK | mvn compile 通过 |
| 单元测试 | ✅ 50/50 pass | finance-service 全量测试通过 |

**真验修复**（fix `832a1fa`）：
- `resetTarget` 未导出致 TS2724 → 补 export 函数
- `variant="warning"` 类型错误 TS2322 → 改 `variant="ghost"`

---

## §5 如实说明

1. **StorageService 为底座接口**：当前仅 LocalStorageService 本地文件系统实现，后续可接 OSS/MinIO 实现类，本卡不施工对接。
2. **aggregatedRevenue 读时聚合**：非持久化字段，每次 list/view 时从 RevenueMonthly 表实时聚合计算，大数据量场景后续可引入缓存。
3. **reset() 状态守卫**：仅 REJECTED 态可退回 DRAFT，其他状态返回 400，与退款/转卡驳回口径一致（不可重提，须新建）。
4. **竞品调研结论**：5 选型方向 23 候选，详见 DESIGN §0-§3，本卡施工项为调研后优先级最高三项。
5. **数字未变**：✅109/🔧1/⬜55=166，无 ⬜→✅、无 🔧 消解。

---

## §6 五落点

| 落点 | 文件 | 状态 |
|------|------|------|
| 施工简报 | 00-history.md L6 上方 | ✅ |
| 倒序 bullet | 01-dashboard.md L19 | ✅ |
| 六列批次行 | 03-timeline.md B69 卡1 行 | ✅ |
| 注记勾销 | 04-backlog.md L54/L132/L133 | ✅ |
| 哨兵更新 | HANDOFF-AUTO.md L7-L14 | ✅ |

---

## §7 下一批

B69 闭合后，用户总授权队列 B 类全部完成，转入 **C. 大阶段/专项开工**。具体候选须按 C 阶段开工流程做只读侦察＋书面定案后再拆卡。
