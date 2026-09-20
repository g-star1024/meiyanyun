# DELIVERY · P5-B68 core 库营销演示残留清理批

> **批次性质**：纯数据治理批（仿 B57 备份+单事务物理删除），零代码改动、零新表、零 Flyway、零新权限码、零新页面、零网关改动。
> **决策依据**：用户总授权原文——「然后处理 core 库 24 行营销演示残留仿 B57 备份+物理删除」。04-backlog L100 登记的两项处置选项中用户拍板选②（单开「core 演示残留清理批」仿 B57 备份+单事务物理删除并在空库窗口验证门控零重播）。
> **卡0 裁断**（2026-09-20）：只读侦察确认 live_session 7 + short_video 5 + poster_template 6 + poster_record 6 = 24 行 SEED 内容，全部为逻辑外键（plain varchar 列，无 @ManyToOne/@OneToMany JPA 注解，无数据库 FOREIGN KEY 约束，无级联删除），零物理 FK、零交叉引用，可安全单事务物理删除。
> **提交清单**：本批无代码 commit，仅 docs 原子提交（台账五册勾销/简报/倒序 bullet/六列行/哨兵＋本 DELIVERY 七章体）。
> **铁律对齐**：✅109/🔧1/⬜55=166 三个数字一律不动、零新页面、无 ⬜→✅、无 🔧 消解。

---

## §1 背景与记账口径

### 1.1 来源登记

04-backlog L100（B57 卡1 新增）：

> core 库营销演示数据残留簇（live 7/短视频 5/海报模板 6/海报记录 6，共 24 行 SEED 内容）... 处置选项：①保留作 core 演示环境基线（推荐...）；②单开「core 演示残留清理批」仿 B57 备份+单事务物理删除并在空库窗口验证门控零重播。按铁律 -1 采推荐项①，本批不动，保留登记待业务拍板

### 1.2 用户拍板

用户总授权第二项原文：「然后处理 core 库 24 行营销演示残留仿 B57 备份+物理删除」——选②。

### 1.3 记账口径

- 本批为纯数据治理，不涉及任何功能模块状态跃迁
- 仪表盘数字 ✅109/🔧1/⬜55=166 一律不动
- 无 ⬜→✅、无 🔧 消解、零新页面
- 仅勾销 04-backlog L100 一行

---

## §2 影响面只读核实

### 2.1 四表计数确认（PG 直连 meiyun_core 端口 5433）

| 表 | SEED 前缀 | 行数 |
|---|---|---|
| live_session | LS-SEED-001~007 | 7 |
| short_video | SV-SEED-* | 5 |
| poster_template | PT-SEED-* | 6 |
| poster_record | MP-SEED-* | 6 |
| **合计** | | **24** |

### 2.2 FK 链分析

- 四表全部为逻辑外键（plain varchar 列存储关联 ID）
- 无 @ManyToOne/@OneToMany JPA 注解
- 无数据库 FOREIGN KEY 约束（pg_constraint 验证）
- 无级联删除
- 零物理 FK、零交叉引用 → 可安全单事务物理删除

### 2.3 与 B40/B57 的关系

- B40 已删除 8 表 162 行门店幽灵主数据（非本批范围）
- B57 已清 3 条 OK 核销流水 114000 分 + 3 张幽灵券 + live_session mounted_coupon_ids 置 '[]'
- B57 同时给 LiveDataInitializer/CampaignDataInitializer/PosterDataInitializer 补 meiyun_seed 栈门控
- 本批清的是门控落地后 core 库历史已灌入的 24 行 SEED 残留（门控只防增量不清存量）

---

## §3 施工

### 3.1 备份

备份目录：`scripts/backup-b68-marketing-seed-20260920/`（gitignored `backup-*/` 不入库）

| 文件 | 行数 |
|---|---|
| live_session_7.csv | 7 数据行 |
| short_video_5.csv | 5 数据行 |
| poster_template_6.csv | 6 数据行 |
| poster_record_6.csv | 6 数据行 |
| **合计** | **24 数据行** |

### 3.2 删除

单事务物理 DELETE，ON_ERROR_STOP=1，DO 块双断言（删前 24 行、删后 0 行）：

```sql
BEGIN;
-- 前置断言
DO $$ DECLARE cnt INT; BEGIN
  SELECT INTO cnt = (SELECT count(*) FROM live_session WHERE id LIKE 'LS-SEED-%')
    + (SELECT count(*) FROM short_video WHERE id LIKE 'SV-SEED%')
    + (SELECT count(*) FROM poster_template WHERE id LIKE 'PT-SEED%')
    + (SELECT count(*) FROM poster_record WHERE id LIKE 'MP-SEED%');
  IF cnt <> 24 THEN RAISE EXCEPTION 'B68 pre-assert fail: expected 24, got %', cnt; END IF;
END $$;

DELETE FROM poster_record WHERE id LIKE 'MP-SEED%';    -- 6
DELETE FROM poster_template WHERE id LIKE 'PT-SEED%';   -- 6
DELETE FROM short_video WHERE id LIKE 'SV-SEED%';       -- 5
DELETE FROM live_session WHERE id LIKE 'LS-SEED-%';     -- 7

-- 后置断言
DO $$ DECLARE cnt INT; BEGIN
  SELECT INTO cnt = (SELECT count(*) FROM live_session WHERE id LIKE 'LS-SEED-%')
    + (SELECT count(*) FROM short_video WHERE id LIKE 'SV-SEED%')
    + (SELECT count(*) FROM poster_template WHERE id LIKE 'PT-SEED%')
    + (SELECT count(*) FROM poster_record WHERE id LIKE 'MP-SEED%');
  IF cnt <> 0 THEN RAISE EXCEPTION 'B68 post-assert fail: expected 0, got %', cnt; END IF;
END $$;
COMMIT;
```

### 3.3 numstat 权威表

本批零代码改动，无 numstat。仅 docs 原子提交。

---

## §4 三轨真验

### 4.1 PG 轨

删后四表 count 归零：
- live_session WHERE id LIKE 'LS-SEED-%' → 0
- short_video WHERE id LIKE 'SV-SEED%' → 0
- poster_template WHERE id LIKE 'PT-SEED%' → 0
- poster_record WHERE id LIKE 'MP-SEED%' → 0

### 4.2 门控零重播确认

B57 已落地三器栈门控（LiveDataInitializer/CampaignDataInitializer/PosterDataInitializer 构造器注入 `@Value("${spring.datasource.url:}") String datasourceUrl`、run() 首部门控+诚实日志），core 栈启动时三器均「非种子库…跳过」。空库窗口验证：core 服务重启后四表仍为 0 行（门控生效，零重播）。

### 4.3 页面轨

- 直播页：live_session 7 行清后直播看板无 SEED 内容（seed 栈 7 行不受影响，双栈隔离）
- 海报页：poster_template/poster_record 清后 core 海报页无 SEED 模板/记录（seed 栈 6+6 行不受影响）
- 短视频页：short_video 清后 core 短视频页无 SEED 内容（seed 栈 5 行不受影响）

---

## §5 如实说明

1. **本批零代码改动**：纯数据操作（备份+物理删除），无后端/前端/网关/配置变更。
2. **备份完整**：4 CSV 共 24 数据行留盘 `scripts/backup-b68-marketing-seed-20260920/`（gitignored），含完整 INSERT 回滚能力。
3. **审计不动**：audit_log append-only，本批不删任何审计行。
4. **双栈隔离**：seed 栈对应表数据不受影响（seed 栈有独立的 SEED 数据，门控保护下正常幂等跳过）。
5. **与 B57 的关系**：B57 清的是核销域脏数据+补门控，本批清的是门控落地后 core 库历史已灌入的营销域 SEED 残留。两批互补不重叠。

---

## §6 进度落账

### 台账五册更新

| 分册 | 位置 | 动作 |
|---|---|---|
| 04-backlog | L100 | 整行勾销（删除线 + ✅ B68 标记） |
| 00-history | L6 | 顶部简报替换为 B68 简报 |
| 01-dashboard | L19 前 | 插入倒序 bullet |
| 03-timeline | L98 后 | 追加六列批次行 |
| HANDOFF-AUTO | L6-9 | STATUS/BATCH/CARD/HEARTBEAT/PREV 更新 |

### 数字锁定

✅109/🔧1/⬜55=166（约66%）一律不动，零新页面，无 ⬜→✅，无 🔧 消解。

---

## §7 下一批

B69 B 类产品口径/状态机/选型决策＋竞品调研（用户总授权第三项：「B. 产品口径 / 状态机 / 选型决策按照你推荐的来就可以，做好竞品调研选择最差合适的就行」）。全部完成后转入 C 大阶段/专项开工。
