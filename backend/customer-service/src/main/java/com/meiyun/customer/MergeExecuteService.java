package com.meiyun.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 撞单合并执行引擎（P5-B84 写期核心，DESIGN-P5-B84 §4 单事务八步落地）。
 *
 * <p>拍板口径：D1 loser 置 merged_into/merged_at（status 不动）；D2 状态机骨架全落地、
 * customer:merge 持有者直接执行（PROPOSED→APPROVED→MERGED 同事务链，审批人=执行人）；
 * D4 逻辑关联表按 §2.2 分类（运营读链路 15 迁 / 统计 AI 快照 4 不迁 / 合规留痕 2 不迁）；
 * D7 已匿名化档案 409 拒合；D8 CROSS_STORE 仅 REGION/GROUP、店长仅 SAME_STORE、POOL 仅超管。
 *
 * <p>幂等：idem_key 唯一索引＋重复返回当前态；未传 idemKey 时服务端兜底 pairId:masterId。
 * 快照留痕（customer_merge_snapshot）仅供审计/对账，本批不提供回滚端点（D5）。
 */
@Service
public class MergeExecuteService {

    private static final ZoneId BJ = ZoneId.of("Asia/Shanghai");
    /** 等级序兜底（member_level.sort_no 缺失时，与 CustomerService.LEVEL_ORDER 同口径）。 */
    private static final Map<String, Integer> LEVEL_ORDER = Map.of(
            "普通", 1, "银卡", 2, "金卡", 3, "钻石", 4, "黑卡", 5);

    /** 迁移子表（表名, 主键列）：8 物理 FK 表除 tag_rel 外的 7 张 + 15 张逻辑关联表（D4 运营读链路）。
     *  合计口径：tag_rel（两步法单独处理）+ 本清单 22 张 = 23 张迁移表，快照 24 行（+customer 行快照）。 */
    private static final List<String[]> MIGRATE_TABLES = List.of(
            new String[]{"member_card", "card_no"},
            new String[]{"appointment", "appt_no"},
            new String[]{"consultation", "consult_id"},
            new String[]{"txn_order", "order_no"},
            new String[]{"writeoff_record", "writeoff_id"},
            new String[]{"repurchase", "repurchase_no"},
            new String[]{"push_record", "push_id"},
            new String[]{"card_ledger", "ledger_id"},
            new String[]{"points_ledger", "ledger_id"},
            new String[]{"emr_record", "emr_no"},
            new String[]{"followup", "id"},
            new String[]{"arrival", "ah_no"},
            new String[]{"checkin_record", "ci_no"},
            new String[]{"consult_plan", "plan_id"},
            new String[]{"triage", "tr_no"},
            new String[]{"arrival_waitlist", "wl_no"},
            new String[]{"writeoff_desk_task", "wd_no"},
            new String[]{"followup_sop_batch", "batch_no"},
            new String[]{"customer_grant", "id"},
            new String[]{"grant_deduction", "id"},
            new String[]{"mall_exchange", "exchange_id"},
            new String[]{"customer_level_history", "id"});

    private final CustomerMergeRepository mergeRepo;
    private final CustomerMergeSnapshotRepository snapshotRepo;
    private final CustomerRepository customerRepo;
    private final MemberLevelRepository levelRepo;
    private final CustomerSearchEventPublisher searchEventPublisher;
    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public MergeExecuteService(CustomerMergeRepository mergeRepo,
                               CustomerMergeSnapshotRepository snapshotRepo,
                               CustomerRepository customerRepo,
                               MemberLevelRepository levelRepo,
                               CustomerSearchEventPublisher searchEventPublisher,
                               JdbcTemplate jdbc,
                               ObjectMapper om) {
        this.mergeRepo = mergeRepo;
        this.snapshotRepo = snapshotRepo;
        this.customerRepo = customerRepo;
        this.levelRepo = levelRepo;
        this.searchEventPublisher = searchEventPublisher;
        this.jdbc = jdbc;
        this.om = om;
    }

    /** 合并执行结果（Controller 组装 EXECUTE 审计 payload 用）。 */
    public record ExecuteResult(Map<String, Integer> movedCounts, Map<String, String> survivorFields) {
        public String movedCountsJson(ObjectMapper om) {
            try {
                return om.writeValueAsString(movedCounts);
            } catch (Exception e) {
                return "{}";
            }
        }

        public String survivorFieldsJson(ObjectMapper om) {
            try {
                return om.writeValueAsString(survivorFields);
            } catch (Exception e) {
                return "{}";
            }
        }
    }

    /** 发起结果：merge 单 + 是否本次新执行（幂等重放为 false，Controller 据此决定是否补审计）。 */
    public record ProposeResult(CustomerMerge merge, boolean executed, ExecuteResult execute) {
    }

    /** 审批结果：是否本次新执行（MERGED 幂等重放为 false）。 */
    public record ApproveResult(CustomerMerge merge, boolean executed, ExecuteResult execute) {
    }

    /** dismiss 结果：是否本次新落痕（同 pair/idemKey 重放为 false，Controller 据此决定是否补审计）。 */
    public record DismissResult(CustomerMerge merge, boolean created) {
    }

    /**
     * 发起合并（D2 直接执行链）：校验 → 落单 PROPOSED→APPROVED → 同事务八步执行 → MERGED。
     * 幂等：idemKey（或兜底键）命中已有单据直接返回当前态，不重复执行、不重复审计。
     */
    @Transactional
    public ProposeResult propose(String pairId, String customerIdA, String customerIdB,
                                 String masterId, String reason, String idemKey) {
        String[] pair = resolvePair(pairId, customerIdA, customerIdB);
        String a = pair[0];
        String b = pair[1];

        // master 定侧先于幂等键计算（兜底键含 masterId）
        // 1. 双行字典序 FOR UPDATE（防并发合并同一对/链式合并；先于实体加载，保证读到锁内最新行）
        lockPair(a, b);
        Customer ca = requireCustomer(a);
        Customer cb = requireCustomer(b);

        Customer master = resolveMaster(ca, cb, masterId);
        Customer loser = master.getCustomerId().equals(a) ? cb : ca;

        String effectiveIdem = (idemKey != null && !idemKey.isBlank())
                ? idemKey : pairId(a, b) + ":" + master.getCustomerId();
        var existing = mergeRepo.findByIdemKey(effectiveIdem);
        if (existing.isPresent()) {
            return new ProposeResult(existing.get(), false, null);
        }

        // 2. 校验链（数据域/撞单证据/角色域闸门/状态守卫）
        validatePair(ca, cb);

        CustomerMerge m = new CustomerMerge();
        m.setMergeId(nextMergeId());
        m.setMasterId(master.getCustomerId());
        m.setMergedId(loser.getCustomerId());
        m.setGroupType(classifyPairType(ca.getStoreCode(), cb.getStoreCode()));
        m.setMatchReasons("PHONE");
        m.setScore(BigDecimal.valueOf(MergeCandidateService.PHONE_MATCH_SCORE));
        m.setReason(reason);
        m.setStatus(CustomerMerge.STATUS_PROPOSED);
        m.setIdemKey(effectiveIdem);
        m.setRequestedBy(DataScope.currentActor());
        mergeRepo.save(m);

        // D2：PROPOSED→APPROVED→MERGED 同事务链，审批人=执行人
        m.setStatus(CustomerMerge.STATUS_APPROVED);
        m.setApprovedBy(m.getRequestedBy());
        m.setApprovedAt(OffsetDateTime.now());

        ExecuteResult er = executeMerge(m, master, loser);
        return new ProposeResult(m, true, er);
    }

    /** 审批通过并执行（状态机骨架：PROPOSED/REVIEWING 单据用；本批直接执行链下一般无存量）。 */
    @Transactional
    public ApproveResult approve(String mergeId) {
        CustomerMerge m = mergeRepo.findById(mergeId)
                .orElseThrow(() -> new CustomerService.NotFound("合并单不存在"));
        if (CustomerMerge.STATUS_MERGED.equals(m.getStatus())) {
            return new ApproveResult(m, false, null);
        }
        if (!CustomerMerge.STATUS_PROPOSED.equals(m.getStatus())
                && !CustomerMerge.STATUS_REVIEWING.equals(m.getStatus())) {
            throw new CustomerService.Conflict("单据已终态，不可审批");
        }
        lockPair(m.getMasterId(), m.getMergedId());
        Customer master = requireCustomer(m.getMasterId());
        Customer loser = requireCustomer(m.getMergedId());
        validatePair(master, loser);
        m.setStatus(CustomerMerge.STATUS_APPROVED);
        m.setApprovedBy(DataScope.currentActor());
        m.setApprovedAt(OffsetDateTime.now());
        ExecuteResult er = executeMerge(m, master, loser);
        return new ApproveResult(m, true, er);
    }

    /** 驳回：PROPOSED/REVIEWING/APPROVED → REJECTED，附理由。 */
    @Transactional
    public CustomerMerge reject(String mergeId, String reason) {
        CustomerMerge m = mergeRepo.findById(mergeId)
                .orElseThrow(() -> new CustomerService.NotFound("合并单不存在"));
        if (CustomerMerge.STATUS_MERGED.equals(m.getStatus())
                || CustomerMerge.STATUS_REJECTED.equals(m.getStatus())
                || CustomerMerge.STATUS_NOT_DUPLICATE.equals(m.getStatus())) {
            throw new CustomerService.Conflict("单据已终态，不可驳回");
        }
        m.setStatus(CustomerMerge.STATUS_REJECTED);
        if (reason != null && !reason.isBlank()) {
            m.setReason(reason.trim());
        }
        return m;
    }

    /**
     * 标记非重复（dismiss）：status=NOT_DUPLICATE 落痕，候选发现排除该 pair（双向）。
     * 幂等：同 pair 已落 NOT_DUPLICATE 或 idemKey 命中时返回已有单据，不重复落痕。
     */
    @Transactional
    public DismissResult dismiss(String customerIdA, String customerIdB, String reason, String idemKey) {
        if (idemKey != null && !idemKey.isBlank()) {
            var hit = mergeRepo.findByIdemKey(idemKey);
            if (hit.isPresent()) {
                return new DismissResult(hit.get(), false);
            }
        }
        String[] pair = resolvePair(null, customerIdA, customerIdB);
        String a = pair[0];
        String b = pair[1];
        // 幂等：同 pair 已落 NOT_DUPLICATE 直接返回（先于校验链，避免「已标记非重复」守卫 409 误伤重放）
        var dup = mergeRepo.findByIdemKey(dismissIdemKey(a, b));
        if (dup.isPresent()) {
            return new DismissResult(dup.get(), false);
        }
        lockPair(a, b);
        Customer ca = requireCustomer(a);
        Customer cb = requireCustomer(b);
        validatePair(ca, cb, false);
        CustomerMerge m = new CustomerMerge();
        m.setMergeId(nextMergeId());
        m.setMasterId(a);
        m.setMergedId(b);
        m.setGroupType(classifyPairType(ca.getStoreCode(), cb.getStoreCode()));
        m.setMatchReasons("PHONE");
        m.setScore(BigDecimal.valueOf(MergeCandidateService.PHONE_MATCH_SCORE));
        m.setReason(reason);
        m.setStatus(CustomerMerge.STATUS_NOT_DUPLICATE);
        m.setIdemKey((idemKey != null && !idemKey.isBlank()) ? idemKey : dismissIdemKey(a, b));
        m.setRequestedBy(DataScope.currentActor());
        return new DismissResult(mergeRepo.save(m), true);
    }

    /** 合并留痕分页列表：pair 双侧可读的行才返回（不泄露越权 pair 存在性），MERGED 行附迁移总行数。 */
    @Transactional(readOnly = true)
    public Page<MergeRowDTO> list(String status, Pageable pageable) {
        Page<CustomerMerge> page = (status == null || status.isBlank())
                ? mergeRepo.findAllByOrderByCreatedAtDesc(pageable)
                : mergeRepo.findByStatusOrderByCreatedAtDesc(status.trim(), pageable);
        Map<String, Customer> customers = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        for (CustomerMerge m : page.getContent()) {
            ids.add(m.getMasterId());
            ids.add(m.getMergedId());
        }
        if (!ids.isEmpty()) {
            for (Customer c : customerRepo.findAllById(ids)) {
                customers.put(c.getCustomerId(), c);
            }
        }
        List<MergeRowDTO> rows = new ArrayList<>();
        for (CustomerMerge m : page.getContent()) {
            Customer master = customers.get(m.getMasterId());
            Customer merged = customers.get(m.getMergedId());
            if (master == null || merged == null) {
                continue;
            }
            if (!DataScope.canReadOwned(master.getStoreCode(), master.getOwnerStaffId())
                    || !DataScope.canReadOwned(merged.getStoreCode(), merged.getOwnerStaffId())) {
                continue;
            }
            int movedTotal = 0;
            if (CustomerMerge.STATUS_MERGED.equals(m.getStatus())) {
                for (CustomerMergeSnapshot s : snapshotRepo.findByMergeId(m.getMergeId())) {
                    if (!"customer".equals(s.getTableName())) {
                        movedTotal += s.getMovedCount() == null ? 0 : s.getMovedCount();
                    }
                }
            }
            rows.add(new MergeRowDTO(m, master.getName(), merged.getName(), movedTotal));
        }
        return new org.springframework.data.domain.PageImpl<>(rows, pageable, page.getTotalElements());
    }

    /** 留痕列表行 DTO（entity + 双侧姓名富化 + 迁移总行数）。 */
    public record MergeRowDTO(CustomerMerge merge, String masterName, String mergedName, int movedTotal) {
    }

    // ---- 八步执行引擎（§4.1，调用方已持双行锁＋单据行） ----

    private ExecuteResult executeMerge(CustomerMerge m, Customer master, Customer loser) {
        String masterId = master.getCustomerId();
        String loserId = loser.getCustomerId();

        // 3. 快照：loser 全字段 + customer 行快照（table_name='customer'）
        snapshotLoser(m.getMergeId(), loser);

        // 4a. customer_tag_rel 两步法：DELETE 交集防撞 (customer_id,tag_id) 联合主键 → UPDATE 差集
        Map<String, Integer> movedCounts = new LinkedHashMap<>();
        List<String> loserTagIds = jdbc.queryForList(
                "select tag_id from customer_tag_rel where customer_id = ?", String.class, loserId);
        int deleted = jdbc.update(
                "delete from customer_tag_rel where customer_id = ? and tag_id in "
                        + "(select tag_id from customer_tag_rel where customer_id = ?)", loserId, masterId);
        int tagMoved = jdbc.update(
                "update customer_tag_rel set customer_id = ? where customer_id = ?", masterId, loserId);
        snapshotRepo.save(new CustomerMergeSnapshot(
                m.getMergeId(), "customer_tag_rel", toJson(loserTagIds), tagMoved));
        movedCounts.put("customer_tag_rel", tagMoved);
        if (deleted > 0) {
            movedCounts.put("customer_tag_rel_dedup", deleted);
        }

        // 4b/4c. 其余 22 张子表逐表 UPDATE（各记 row_ids 快照 + moved_count）
        for (String[] t : MIGRATE_TABLES) {
            List<String> rowIds = jdbc.queryForList(
                    "select " + t[1] + "::text from " + t[0] + " where customer_id = ?", String.class, loserId);
            int moved = jdbc.update(
                    "update " + t[0] + " set customer_id = ? where customer_id = ?", masterId, loserId);
            snapshotRepo.save(new CustomerMergeSnapshot(m.getMergeId(), t[0], toJson(rowIds), moved));
            movedCounts.put(t[0], moved);
        }

        // 5. Survivorship 聚合回写 master
        Map<String, String> survivorFields = applySurvivorship(master, loser);
        customerRepo.save(master);

        // 5a. 积分锚点续链：loser 流水迁入后，master 侧补一条零变动校准流水，
        // balance_after=合并后期末余额，保证按 balance_after 逐行锚定对账在合并边界不断链
        Integer pointsMoved = movedCounts.get("points_ledger");
        if (pointsMoved != null && pointsMoved > 0) {
            jdbc.update(
                    "insert into points_ledger (customer_id, change_amt, balance_after, reason, created_at) "
                            + "values (?, 0, ?, ?, now())",
                    masterId, master.getPoints(),
                    "合并校准：档案 " + loserId + " 并入，锚定期末余额");
        }

        // 6. loser 处置：merged_into/merged_at；status/name/phone 等一律不动（保留撞单证据链）
        loser.setMergedInto(masterId);
        loser.setMergedAt(OffsetDateTime.now());
        customerRepo.save(loser);

        // 7. merge 行终态
        m.setStatus(CustomerMerge.STATUS_MERGED);
        m.setExecutedAt(OffsetDateTime.now());
        mergeRepo.save(m);

        // ES 近线刷新：主档案聚合后文档更新；loser 文档带 merged_into（读侧过滤横切兜底）
        searchEventPublisher.emitUpsert(masterId);
        searchEventPublisher.emitUpsert(loserId);
        return new ExecuteResult(movedCounts, survivorFields);
    }

    /** Survivorship：等级只升不降、累计字段双方和、画像文本字段 master 空取 loser（不拼接防污染）。 */
    private Map<String, String> applySurvivorship(Customer master, Customer loser) {
        Map<String, String> changed = new LinkedHashMap<>();
        int masterOrd = levelOrder(master.getLevel());
        int loserOrd = levelOrder(loser.getLevel());
        if (loserOrd > masterOrd) {
            changed.put("level", master.getLevel() + "→" + loser.getLevel());
            master.setLevel(loser.getLevel());
        }
        BigDecimal spend = nvl(master.getTotalSpend()).add(nvl(loser.getTotalSpend()));
        changed.put("totalSpend", nvl(master.getTotalSpend()) + "+" + nvl(loser.getTotalSpend()) + "=" + spend);
        master.setTotalSpend(spend);
        long points = nvl(master.getPoints()) + nvl(loser.getPoints());
        changed.put("points", nvl(master.getPoints()) + "+" + nvl(loser.getPoints()) + "=" + points);
        master.setPoints(points);
        int visits = nvl(master.getVisitCount()) + nvl(loser.getVisitCount());
        changed.put("visitCount", nvl(master.getVisitCount()) + "+" + nvl(loser.getVisitCount()) + "=" + visits);
        master.setVisitCount(visits);

        if (master.getAge() == null && loser.getAge() != null) {
            master.setAge(loser.getAge());
            changed.put("age", "fill");
        }
        if (master.getBirthDate() == null && loser.getBirthDate() != null) {
            master.setBirthDate(loser.getBirthDate());
            changed.put("birthDate", "fill");
        }
        if (isBlank(master.getSkinType()) && !isBlank(loser.getSkinType())) {
            master.setSkinType(loser.getSkinType());
            changed.put("skinType", "fill");
        }
        if (isEmpty(master.getConcerns()) && !isEmpty(loser.getConcerns())) {
            master.setConcerns(loser.getConcerns());
            changed.put("concerns", "fill");
        }
        // 过敏史：master 未声明（无过敏/阳性项均空）才取 loser，已声明一概不动
        boolean masterAllergyDeclared = Boolean.TRUE.equals(master.getAllergyNone()) || !isEmpty(master.getAllergies());
        if (!masterAllergyDeclared) {
            if (Boolean.TRUE.equals(loser.getAllergyNone())) {
                master.setAllergyNone(true);
                master.setAllergies(List.of());
                changed.put("allergyNone", "fill");
            } else if (!isEmpty(loser.getAllergies())) {
                master.setAllergies(loser.getAllergies());
                changed.put("allergies", "fill");
            }
        }
        if (isBlank(master.getAllergyNote()) && !isBlank(loser.getAllergyNote())) {
            master.setAllergyNote(loser.getAllergyNote());
            changed.put("allergyNote", "fill");
        }
        if (isEmpty(master.getIntentProjects()) && !isEmpty(loser.getIntentProjects())) {
            master.setIntentProjects(loser.getIntentProjects());
            changed.put("intentProjects", "fill");
        }
        if (isBlank(master.getIntentLevel()) && !isBlank(loser.getIntentLevel())) {
            master.setIntentLevel(loser.getIntentLevel());
            changed.put("intentLevel", "fill");
        }
        if (isBlank(master.getBudget()) && !isBlank(loser.getBudget())) {
            master.setBudget(loser.getBudget());
            changed.put("budget", "fill");
        }
        if (isBlank(master.getIntentNote()) && !isBlank(loser.getIntentNote())) {
            master.setIntentNote(loser.getIntentNote());
            changed.put("intentNote", "fill");
        }
        return changed;
    }

    // ---- 校验与闸门 ----

    /** propose/approve 校验链：含「已标记非重复」守卫。 */
    private void validatePair(Customer ca, Customer cb) {
        validatePair(ca, cb, true);
    }

    /** pair 校验链：双侧可读（404 不泄露存在性）→ 撞单证据（归一化手机号一致）→ D8 角色域闸门 → 状态守卫。 */
    private void validatePair(Customer ca, Customer cb, boolean checkNotDuplicate) {
        if (!DataScope.canReadOwned(ca.getStoreCode(), ca.getOwnerStaffId())
                || !DataScope.canReadOwned(cb.getStoreCode(), cb.getOwnerStaffId())) {
            throw new CustomerService.NotFound("数据不存在或无权查看");
        }
        String pa = MergeCandidateService.normalizePhone(ca.getPhone());
        String pb = MergeCandidateService.normalizePhone(cb.getPhone());
        if (pa == null || pa.isBlank() || !pa.equals(pb)) {
            throw new CustomerService.BadReq("两档案手机号归一化后不一致，不满足撞单合并条件");
        }
        enforceRoleGate(classifyPairType(ca.getStoreCode(), cb.getStoreCode()));
        if (ca.getMergedInto() != null || cb.getMergedInto() != null) {
            throw new CustomerService.Conflict("档案已被合并，不可再次参与合并");
        }
        if (ca.getAnonymizedAt() != null || cb.getAnonymizedAt() != null) {
            throw new CustomerService.Conflict("已匿名化档案不可合并");
        }
        if (mergeRepo.existsByMergedIdAndStatus(ca.getCustomerId(), CustomerMerge.STATUS_MERGED)
                || mergeRepo.existsByMergedIdAndStatus(cb.getCustomerId(), CustomerMerge.STATUS_MERGED)) {
            throw new CustomerService.Conflict("档案已被合并，不可再次参与合并");
        }
        if (checkNotDuplicate && mergeRepo.existsPairWithStatus(ca.getCustomerId(), cb.getCustomerId(),
                CustomerMerge.STATUS_NOT_DUPLICATE)) {
            throw new CustomerService.Conflict("该对客户已标记为非重复档案");
        }
    }

    /** D8 角色域闸门：CROSS_STORE 仅 REGION/GROUP；店长（STORE/SELF）仅 SAME_STORE；POOL 仅超管。 */
    private void enforceRoleGate(String groupType) {
        LoginUser u = DataScope.current();
        if (u == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if ("POOL".equals(groupType)) {
            if (!u.isSuper()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "公海档案合并需超管权限");
            }
            return;
        }
        if ("CROSS_STORE".equals(groupType)) {
            boolean ok = u.isSuper() || DataScope.SCOPE_REGION.equals(u.scope())
                    || DataScope.SCOPE_GROUP.equals(u.scope()) || DataScope.SCOPE_BRAND.equals(u.scope());
            if (!ok) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "跨店合并需区域经理或超管权限");
            }
        }
    }

    // ---- 内部工具 ----

    /** pair 解析：pairId（MC-A-B）优先，否则 customerIdA/B；返回字典序 [小, 大]。 */
    private String[] resolvePair(String pairId, String customerIdA, String customerIdB) {
        String a = customerIdA;
        String b = customerIdB;
        if (pairId != null && !pairId.isBlank()) {
            String p = pairId.trim();
            if (!p.startsWith("MC-")) {
                throw new CustomerService.BadReq("候选对编号格式不正确");
            }
            String body = p.substring(3);
            int sep = body.indexOf('-');
            if (sep <= 0 || sep >= body.length() - 1) {
                throw new CustomerService.BadReq("候选对编号格式不正确");
            }
            a = body.substring(0, sep);
            b = body.substring(sep + 1);
        }
        if (a == null || a.isBlank() || b == null || b.isBlank()) {
            throw new CustomerService.BadReq("请提供候选对编号或两个客户编号");
        }
        a = a.trim();
        b = b.trim();
        if (a.equals(b)) {
            throw new CustomerService.BadReq("同一档案不可与自身合并");
        }
        return a.compareTo(b) <= 0 ? new String[]{a, b} : new String[]{b, a};
    }

    /** 组类型（两侧口径，与 MergeCandidateService.classifyGroupType 组口径一致）：全空=POOL；相同非空=SAME_STORE；其余=CROSS_STORE。 */
    private String classifyPairType(String storeA, String storeB) {
        boolean ea = storeA == null || storeA.isBlank();
        boolean eb = storeB == null || storeB.isBlank();
        if (ea && eb) {
            return "POOL";
        }
        if (!ea && !eb && storeA.equals(storeB)) {
            return "SAME_STORE";
        }
        return "CROSS_STORE";
    }

    /** master 定侧：指定则必须是对内一侧；默认创建时间最早（并列取 customerId 较小者）。 */
    private Customer resolveMaster(Customer ca, Customer cb, String masterId) {
        if (masterId != null && !masterId.isBlank()) {
            String id = masterId.trim();
            if (!id.equals(ca.getCustomerId()) && !id.equals(cb.getCustomerId())) {
                throw new CustomerService.BadReq("保留方必须是候选对中的一侧");
            }
            return id.equals(ca.getCustomerId()) ? ca : cb;
        }
        OffsetDateTime ta = ca.getCreatedAt();
        OffsetDateTime tb = cb.getCreatedAt();
        if (ta == null && tb == null) {
            return ca;
        }
        if (ta == null) {
            return cb;
        }
        if (tb == null) {
            return ca;
        }
        return ta.compareTo(tb) <= 0 ? ca : cb;
    }

    /** 双行字典序 FOR UPDATE（固定加锁顺序防死锁）；行不存在抛 404。 */
    private void lockPair(String a, String b) {
        String first = a.compareTo(b) <= 0 ? a : b;
        String second = first.equals(a) ? b : a;
        lockOne(first);
        lockOne(second);
    }

    private void lockOne(String customerId) {
        try {
            jdbc.queryForObject(
                    "select customer_id from customer where customer_id = ? for update", String.class, customerId);
        } catch (EmptyResultDataAccessException e) {
            throw new CustomerService.NotFound("数据不存在或无权查看");
        }
    }

    private Customer requireCustomer(String customerId) {
        return customerRepo.findById(customerId)
                .orElseThrow(() -> new CustomerService.NotFound("数据不存在或无权查看"));
    }

    /** 单号取号：MG+8位日期+"-"+6位序号（当日 max+1，查库递增防重号，synchronized 防进程内并发）。 */
    private synchronized String nextMergeId() {
        String prefix = "MG" + LocalDate.now(BJ).format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        String max = mergeRepo.maxIdForDate(prefix);
        int seq = 1;
        if (max != null && max.length() >= prefix.length() + 6) {
            seq = Integer.parseInt(max.substring(prefix.length(), prefix.length() + 6)) + 1;
        }
        return prefix + String.format("%06d", seq);
    }

    /** loser 全字段快照（table_name='customer'，row_ids=[merged_id]，loser_before=jsonb 全字段）。 */
    private void snapshotLoser(String mergeId, Customer loser) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("customerId", loser.getCustomerId());
        before.put("name", loser.getName());
        before.put("phone", loser.getPhone());
        before.put("gender", loser.getGender());
        before.put("birthDate", loser.getBirthDate() == null ? null : loser.getBirthDate().toString());
        before.put("level", loser.getLevel());
        before.put("storeCode", loser.getStoreCode());
        before.put("channel", loser.getChannel());
        before.put("totalSpend", loser.getTotalSpend());
        before.put("visitCount", loser.getVisitCount());
        before.put("ownerStaffId", loser.getOwnerStaffId());
        before.put("age", loser.getAge());
        before.put("skinType", loser.getSkinType());
        before.put("concerns", loser.getConcerns());
        before.put("allergyNone", loser.getAllergyNone());
        before.put("allergies", loser.getAllergies());
        before.put("allergyNote", loser.getAllergyNote());
        before.put("intentProjects", loser.getIntentProjects());
        before.put("intentLevel", loser.getIntentLevel());
        before.put("budget", loser.getBudget());
        before.put("intentNote", loser.getIntentNote());
        before.put("points", loser.getPoints());
        before.put("status", loser.getStatus());
        before.put("createdAt", loser.getCreatedAt() == null ? null : loser.getCreatedAt().toString());
        CustomerMergeSnapshot snap = new CustomerMergeSnapshot(
                mergeId, "customer", toJson(List.of(loser.getCustomerId())), 1);
        snap.setLoserBefore(toJson(before));
        snapshotRepo.save(snap);
    }

    private int levelOrder(String level) {
        if (level == null) {
            return 0;
        }
        return levelRepo.findById(level)
                .map(MemberLevel::getSortNo)
                .filter(sn -> sn != null && sn > 0)
                .orElseGet(() -> LEVEL_ORDER.getOrDefault(level, 0));
    }

    private String pairId(String a, String b) {
        return "MC-" + a + "-" + b;
    }

    private String dismissIdemKey(String a, String b) {
        return "ND:" + a + ":" + b;
    }

    private String toJson(Object v) {
        try {
            return om.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("快照序列化失败", e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isEmpty(List<String> list) {
        return list == null || list.isEmpty();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static long nvl(Long v) {
        return v == null ? 0L : v;
    }

    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }
}
