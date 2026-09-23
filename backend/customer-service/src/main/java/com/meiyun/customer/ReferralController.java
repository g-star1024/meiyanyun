package com.meiyun.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P5-B86 转介绍：卡1 七端点（page/stats/创建/确认/到访/成交/拒绝）＋卡2 奖励三端点（登记/发放/驳回）。
 * 状态机：PENDING→CONFIRMED→VISITED→DEAL；PENDING→REJECTED；PENDING/CONFIRMED→EXPIRED（卡2 Job）。
 * D3-A：EXPIRED/REJECTED 释放绑定，被推荐人可被重新推荐（部分唯一索引 uk_referral_referee_active）。
 * D4-A：奖励 v1 手动登记，idem_key={referralId}:{triggerEvent}:{rewardType} 幂等（v2 自动发放挂钩点）。
 */
@RestController
@RequestMapping("/api/customer/referral")
public class ReferralController {

    private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "CONFIRMED", "VISITED", "DEAL");
    /** 奖励类型白名单（与 V44 chk_referral_reward_type CHECK 约束一致，铁律 6 先查真实约束）。 */
    private static final List<String> REWARD_TYPES = List.of("POINT", "GRANT", "COUPON", "COMMISSION");
    /** 触发事件白名单（与 V44 chk_referral_reward_event CHECK 约束一致）。 */
    private static final List<String> TRIGGER_EVENTS = List.of("CONFIRMED", "VISITED", "DEAL");

    private final ReferralRepository referralRepo;
    private final CustomerRepository customerRepo;
    private final ReferralRewardRepository rewardRepo;

    @Autowired
    private AuditRecorder audit;

    private final ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());

    public ReferralController(ReferralRepository referralRepo, CustomerRepository customerRepo,
                              ReferralRewardRepository rewardRepo) {
        this.referralRepo = referralRepo;
        this.customerRepo = customerRepo;
        this.rewardRepo = rewardRepo;
    }

    /** 分页列表：status/storeCode/referrerCustomerId/refereeCustomerId/kw（单号模糊），富化推荐人/被推荐人姓名+手机。 */
    @GetMapping("/page")
    @RequirePerm("referral:view")
    public Page<Map<String, Object>> page(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String storeCode,
            @RequestParam(required = false) String referrerCustomerId,
            @RequestParam(required = false) String refereeCustomerId,
            @RequestParam(required = false) String kw) {
        Specification<Referral> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (status != null && !status.isBlank()) ps.add(cb.equal(root.get("status"), status));
            if (storeCode != null && !storeCode.isBlank()) ps.add(cb.equal(root.get("storeCode"), storeCode));
            if (referrerCustomerId != null && !referrerCustomerId.isBlank())
                ps.add(cb.equal(root.get("referrerCustomerId"), referrerCustomerId));
            if (refereeCustomerId != null && !refereeCustomerId.isBlank())
                ps.add(cb.equal(root.get("refereeCustomerId"), refereeCustomerId));
            if (kw != null && !kw.isBlank())
                ps.add(cb.like(root.get("referralId"), "%" + kw.trim() + "%"));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        spec = spec.and(DataScope.storeSpec("storeCode"));
        Page<Referral> p = referralRepo.findAll(spec, pageable);
        Set<String> ids = new HashSet<>();
        Set<String> referrerIds = new HashSet<>();
        p.getContent().forEach(r -> {
            ids.add(r.getReferrerCustomerId());
            ids.add(r.getRefereeCustomerId());
            referrerIds.add(r.getReferrerCustomerId());
        });
        Map<String, Customer> custMap = new HashMap<>();
        if (!ids.isEmpty()) customerRepo.findAllById(ids).forEach(c -> custMap.put(c.getCustomerId(), c));
        // 卡3 前端接真富化：最新一条奖励 + 推荐人等级 + 累计推荐数（批量查询防 N+1，HashMap 空安全）
        List<String> refIds = p.getContent().stream().map(Referral::getReferralId).toList();
        Map<String, ReferralReward> latestReward = new HashMap<>();
        if (!refIds.isEmpty()) rewardRepo.findByReferralIdInOrderByCreatedAtDesc(refIds)
                .forEach(w -> latestReward.putIfAbsent(w.getReferralId(), w));
        Map<String, Long> referrerTotal = new HashMap<>();
        if (!referrerIds.isEmpty()) referralRepo.countGroupByReferrer(referrerIds)
                .forEach(row -> referrerTotal.put((String) row[0], (Long) row[1]));
        return p.map(r -> {
            Map<String, Object> m = row(r, custMap);
            Customer referrer = custMap.get(r.getReferrerCustomerId());
            m.put("referrerLevel", referrer != null ? referrer.getLevel() : null);
            m.put("referrerTotal", referrerTotal.getOrDefault(r.getReferrerCustomerId(), 0L));
            ReferralReward w = latestReward.get(r.getReferralId());
            if (w != null) {
                m.put("rewardId", w.getRewardId());
                m.put("rewardType", w.getRewardType());
                m.put("rewardAmountCents", w.getAmountCents());
                m.put("rewardPoints", w.getPoints());
                m.put("rewardStatus", w.getStatus());
                m.put("rewardPaidAt", w.getGrantedAt());
            }
            return m;
        });
    }

    /** 四 KPI：总量 / 待确认 / 已到访 / 成交金额（分，DEAL 态求和）。 */
    @GetMapping("/stats")
    @RequirePerm("referral:view")
    public Map<String, Object> stats() {
        Specification<Referral> scope = DataScope.storeSpec("storeCode");
        Map<String, Object> m = new HashMap<>();
        m.put("total", referralRepo.count(scope));
        m.put("pending", referralRepo.count(scope.and(statusIs("PENDING"))));
        m.put("visited", referralRepo.count(scope.and(statusIs("VISITED"))));
        m.put("dealAmountCents", referralRepo.findAll(scope.and(statusIs("DEAL"))).stream()
                .mapToLong(r -> r.getDealAmountCents() != null ? r.getDealAmountCents() : 0L).sum());
        return m;
    }

    private static Specification<Referral> statusIs(String status) {
        return (root, q, cb) -> cb.equal(root.get("status"), status);
    }

    /** 创建绑定：推荐人/被推荐人存在且非同人；进行态查重 422；clientToken 幂等重放。 */
    @PostMapping
    @RequirePerm("referral:edit")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        String clientToken = str(body.get("clientToken"));
        if (clientToken != null) {
            var dup = referralRepo.findFirstByClientToken(clientToken);
            if (dup.isPresent()) return row(dup.get(), Map.of());
        }
        String referrerId = str(body.get("referrerCustomerId"));
        String refereeId = str(body.get("refereeCustomerId"));
        if (referrerId == null) throw new CustomerService.BadReq("推荐人客户号必填");
        if (refereeId == null) throw new CustomerService.BadReq("被推荐人客户号必填");
        if (referrerId.equals(refereeId)) throw new CustomerService.BadReq("推荐人与被推荐人不能是同一人");
        Customer referrer = customerRepo.findById(referrerId)
                .orElseThrow(() -> new CustomerService.NotFound("推荐人客户不存在"));
        Customer referee = customerRepo.findById(refereeId)
                .orElseThrow(() -> new CustomerService.NotFound("被推荐人客户不存在"));
        if (referee.getMergedInto() != null && !referee.getMergedInto().isBlank())
            throw new CustomerService.Unprocessable("被推荐人已被合并，不可绑定");
        if (referee.getStoreCode() == null || referee.getStoreCode().isBlank())
            throw new CustomerService.Unprocessable("被推荐人暂无归属门店，不可绑定");
        if (!DataScope.canReadOwned(referee.getStoreCode(), null))
            throw new CustomerService.NotFound("数据不存在或无权查看");
        if (referralRepo.existsByRefereeCustomerIdAndStatusIn(refereeId, ACTIVE_STATUSES))
            throw new CustomerService.Unprocessable("该客户已有进行中的转介绍绑定");
        int validDays = 30;
        Object vd = body.get("validDays");
        if (vd instanceof Number n) {
            validDays = n.intValue();
            if (validDays < 1 || validDays > 365) throw new CustomerService.BadReq("有效天数需在 1-365 之间");
        }
        Referral r = new Referral();
        r.setReferralId(nextReferralNo());
        r.setReferrerCustomerId(referrerId);
        r.setRefereeCustomerId(refereeId);
        r.setCampaignId(str(body.get("campaignId")));
        r.setValidDays(validDays);
        r.setStoreCode(referee.getStoreCode());
        r.setRemark(clip(str(body.get("remark")), 256));
        r.setCreatedBy(DataScope.currentActor());
        r.setClientToken(clientToken);
        try {
            r = referralRepo.save(r);
        } catch (DataIntegrityViolationException e) {
            throw new CustomerService.Unprocessable("该客户已有进行中的转介绍绑定或重复提交");
        }
        audit.record("REFERRAL", r.getReferralId(), DataScope.currentActor(), "CREATE", json(r));
        return row(r, Map.of(referrerId, referrer, refereeId, referee));
    }

    /** 确认：PENDING→CONFIRMED。 */
    @PutMapping("/{id}/confirm")
    @RequirePerm("referral:edit")
    public Map<String, Object> confirm(@PathVariable String id) {
        Referral r = getOwned(id);
        requireStatus(r, "PENDING", "确认");
        r.setStatus("CONFIRMED");
        r.setConfirmedAt(OffsetDateTime.now());
        r = referralRepo.save(r);
        audit.record("REFERRAL", r.getReferralId(), DataScope.currentActor(), "CONFIRM", json(r));
        return row(r, Map.of());
    }

    /** 到访：CONFIRMED→VISITED。 */
    @PutMapping("/{id}/visit")
    @RequirePerm("referral:edit")
    public Map<String, Object> visit(@PathVariable String id) {
        Referral r = getOwned(id);
        requireStatus(r, "CONFIRMED", "到访登记");
        r.setStatus("VISITED");
        r.setVisitedAt(OffsetDateTime.now());
        r = referralRepo.save(r);
        audit.record("REFERRAL", r.getReferralId(), DataScope.currentActor(), "VISIT", json(r));
        return row(r, Map.of());
    }

    /** 成交：VISITED→DEAL，成交金额（分）必填且 > 0。 */
    @PutMapping("/{id}/deal")
    @RequirePerm("referral:edit")
    public Map<String, Object> deal(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Referral r = getOwned(id);
        requireStatus(r, "VISITED", "成交登记");
        Object amt = body.get("dealAmountCents");
        if (!(amt instanceof Number n) || n.longValue() <= 0)
            throw new CustomerService.BadReq("成交金额（分）必填且需大于 0");
        r.setStatus("DEAL");
        r.setDealAt(OffsetDateTime.now());
        r.setDealAmountCents(n.longValue());
        r = referralRepo.save(r);
        audit.record("REFERRAL", r.getReferralId(), DataScope.currentActor(), "DEAL", json(r));
        return row(r, Map.of());
    }

    /** 拒绝：PENDING→REJECTED，拒绝原因必填。 */
    @PutMapping("/{id}/reject")
    @RequirePerm("referral:edit")
    public Map<String, Object> reject(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Referral r = getOwned(id);
        requireStatus(r, "PENDING", "拒绝");
        String reason = str(body.get("rejectReason"));
        if (reason == null) throw new CustomerService.BadReq("拒绝原因必填");
        r.setStatus("REJECTED");
        r.setRejectedAt(OffsetDateTime.now());
        r.setRejectReason(clip(reason, 128));
        r = referralRepo.save(r);
        audit.record("REFERRAL", r.getReferralId(), DataScope.currentActor(), "REJECT", json(r));
        return row(r, Map.of());
    }

    /** 奖励登记（D4-A 手动登记）：idem_key 幂等，同触发事件同类型重复登记返回既有记录不重复落库。 */
    @PostMapping("/{id}/rewards")
    @RequirePerm("referral:approve")
    public Map<String, Object> createReward(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Referral r = getOwned(id);
        String rewardType = str(body.get("rewardType"));
        if (rewardType == null || !REWARD_TYPES.contains(rewardType))
            throw new CustomerService.BadReq("奖励类型需为 POINT/GRANT/COUPON/COMMISSION");
        String triggerEvent = str(body.get("triggerEvent"));
        if (triggerEvent == null || !TRIGGER_EVENTS.contains(triggerEvent))
            throw new CustomerService.BadReq("触发事件需为 CONFIRMED/VISITED/DEAL");
        Long amountCents = longOrNull(body.get("amountCents"));
        Long points = longOrNull(body.get("points"));
        if ("POINT".equals(rewardType)) {
            if (points == null || points <= 0) throw new CustomerService.BadReq("积分奖励需填写大于 0 的积分");
            amountCents = null;
        } else if ("GRANT".equals(rewardType) || "COMMISSION".equals(rewardType)) {
            if (amountCents == null || amountCents <= 0)
                throw new CustomerService.BadReq("赠金/佣金奖励需填写大于 0 的金额（分）");
            points = null;
        } else {
            if (amountCents != null && amountCents <= 0) throw new CustomerService.BadReq("金额（分）需大于 0");
            if (points != null && points <= 0) throw new CustomerService.BadReq("积分需大于 0");
        }
        String idemKey = r.getReferralId() + ":" + triggerEvent + ":" + rewardType;
        var dup = rewardRepo.findFirstByIdemKey(idemKey);
        if (dup.isPresent()) return rewardRow(dup.get());
        ReferralReward w = new ReferralReward();
        w.setRewardId(nextRewardNo());
        w.setReferralId(r.getReferralId());
        w.setRewardType(rewardType);
        w.setTriggerEvent(triggerEvent);
        w.setAmountCents(amountCents);
        w.setPoints(points);
        w.setIdemKey(idemKey);
        w.setRemark(clip(str(body.get("remark")), 256));
        try {
            w = rewardRepo.save(w);
        } catch (DataIntegrityViolationException e) {
            var existing = rewardRepo.findFirstByIdemKey(idemKey);
            if (existing.isPresent()) return rewardRow(existing.get());
            throw new CustomerService.Conflict("同类奖励已登记，请勿重复提交");
        }
        audit.record("REFERRAL", r.getReferralId(), DataScope.currentActor(), "REWARD_CREATE", json(w));
        return rewardRow(w);
    }

    /** 奖励发放确认：PENDING→GRANTED。 */
    @PutMapping("/rewards/{rewardId}/grant")
    @RequirePerm("referral:approve")
    public Map<String, Object> grantReward(@PathVariable String rewardId) {
        ReferralReward w = getRewardOwned(rewardId);
        requireRewardPending(w, "发放确认");
        w.setStatus("GRANTED");
        w.setGrantedBy(DataScope.currentActor());
        w.setGrantedAt(OffsetDateTime.now());
        w = rewardRepo.save(w);
        audit.record("REFERRAL", w.getReferralId(), DataScope.currentActor(), "REWARD_GRANT", json(w));
        return rewardRow(w);
    }

    /** 奖励驳回：PENDING→REJECTED，驳回原因可选（写入 remark）。 */
    @PutMapping("/rewards/{rewardId}/reject")
    @RequirePerm("referral:approve")
    public Map<String, Object> rejectReward(@PathVariable String rewardId,
                                            @RequestBody(required = false) Map<String, Object> body) {
        ReferralReward w = getRewardOwned(rewardId);
        requireRewardPending(w, "驳回");
        w.setStatus("REJECTED");
        String reason = body != null ? str(body.get("reason")) : null;
        if (reason != null) w.setRemark(clip(reason, 256));
        w = rewardRepo.save(w);
        audit.record("REFERRAL", w.getReferralId(), DataScope.currentActor(), "REWARD_REJECT", json(w));
        return rewardRow(w);
    }

    // ---- 内部 ----

    private Referral getOwned(String id) {
        Referral r = referralRepo.findById(id)
                .orElseThrow(() -> new CustomerService.NotFound("转介绍单不存在"));
        if (!DataScope.canReadOwned(r.getStoreCode(), null))
            throw new CustomerService.NotFound("数据不存在或无权查看");
        return r;
    }

    private void requireStatus(Referral r, String expected, String op) {
        if (!expected.equals(r.getStatus()))
            throw new CustomerService.Conflict("当前状态不允许" + op + "（当前：" + r.getStatus() + "）");
    }

    /** 奖励越权收敛：经所属转介绍单 storeCode 判数据域，越权 404（与 getOwned 同口径）。 */
    private ReferralReward getRewardOwned(String rewardId) {
        ReferralReward w = rewardRepo.findById(rewardId)
                .orElseThrow(() -> new CustomerService.NotFound("奖励记录不存在"));
        Referral r = referralRepo.findById(w.getReferralId())
                .orElseThrow(() -> new CustomerService.NotFound("转介绍单不存在"));
        if (!DataScope.canReadOwned(r.getStoreCode(), null))
            throw new CustomerService.NotFound("数据不存在或无权查看");
        return w;
    }

    private void requireRewardPending(ReferralReward w, String op) {
        if (!"PENDING".equals(w.getStatus()))
            throw new CustomerService.Conflict("当前状态不允许" + op + "（当前：" + w.getStatus() + "）");
    }

    private Map<String, Object> rewardRow(ReferralReward w) {
        Map<String, Object> m = new HashMap<>();
        m.put("rewardId", w.getRewardId());
        m.put("referralId", w.getReferralId());
        m.put("rewardType", w.getRewardType());
        m.put("triggerEvent", w.getTriggerEvent());
        m.put("amountCents", w.getAmountCents());
        m.put("points", w.getPoints());
        m.put("status", w.getStatus());
        m.put("grantedBy", w.getGrantedBy());
        m.put("grantedAt", w.getGrantedAt());
        m.put("idemKey", w.getIdemKey());
        m.put("remark", w.getRemark());
        m.put("createdAt", w.getCreatedAt());
        return m;
    }

    private Map<String, Object> row(Referral r, Map<String, Customer> custMap) {
        Map<String, Object> m = new HashMap<>();
        m.put("referralId", r.getReferralId());
        m.put("referrerCustomerId", r.getReferrerCustomerId());
        m.put("refereeCustomerId", r.getRefereeCustomerId());
        m.put("campaignId", r.getCampaignId());
        m.put("status", r.getStatus());
        m.put("validDays", r.getValidDays());
        m.put("boundAt", r.getBoundAt());
        m.put("expireAt", r.getExpireAt());
        m.put("confirmedAt", r.getConfirmedAt());
        m.put("visitedAt", r.getVisitedAt());
        m.put("dealAt", r.getDealAt());
        m.put("expiredAt", r.getExpiredAt());
        m.put("rejectedAt", r.getRejectedAt());
        m.put("rejectReason", r.getRejectReason());
        m.put("dealAmountCents", r.getDealAmountCents());
        m.put("storeCode", r.getStoreCode());
        m.put("remark", r.getRemark());
        m.put("createdBy", r.getCreatedBy());
        m.put("createdAt", r.getCreatedAt());
        Customer referrer = custMap.get(r.getReferrerCustomerId());
        Customer referee = custMap.get(r.getRefereeCustomerId());
        m.put("referrerName", referrer != null ? referrer.getName() : null);
        m.put("referrerPhone", referrer != null ? referrer.getPhone() : null);
        m.put("refereeName", referee != null ? referee.getName() : null);
        m.put("refereePhone", referee != null ? referee.getPhone() : null);
        return m;
    }

    private synchronized String nextReferralNo() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String prefix = "RF" + day + "-";
        long seq = referralRepo.maxSeqOfDay(prefix + "%");
        return prefix + String.format("%06d", seq + 1);
    }

    /** 奖励单号 RW+8位日期-6位当日序号（当日 max+1，synchronized 防重，与转介绍单号同范式）。 */
    private synchronized String nextRewardNo() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String prefix = "RW" + day + "-";
        long seq = rewardRepo.maxSeqOfDay(prefix + "%");
        return prefix + String.format("%06d", seq + 1);
    }

    private static Long longOrNull(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private String json(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
