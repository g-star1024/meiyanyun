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
 * P5-B86 转介绍：卡1 七端点（page/stats/创建/确认/到访/成交/拒绝）。
 * 状态机：PENDING→CONFIRMED→VISITED→DEAL；PENDING→REJECTED；PENDING/CONFIRMED→EXPIRED（卡2 Job）。
 * D3-A：EXPIRED/REJECTED 释放绑定，被推荐人可被重新推荐（部分唯一索引 uk_referral_referee_active）。
 */
@RestController
@RequestMapping("/api/customer/referral")
public class ReferralController {

    private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "CONFIRMED", "VISITED", "DEAL");

    private final ReferralRepository referralRepo;
    private final CustomerRepository customerRepo;

    @Autowired
    private AuditRecorder audit;

    private final ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());

    public ReferralController(ReferralRepository referralRepo, CustomerRepository customerRepo) {
        this.referralRepo = referralRepo;
        this.customerRepo = customerRepo;
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
        p.getContent().forEach(r -> { ids.add(r.getReferrerCustomerId()); ids.add(r.getRefereeCustomerId()); });
        Map<String, Customer> custMap = new HashMap<>();
        if (!ids.isEmpty()) customerRepo.findAllById(ids).forEach(c -> custMap.put(c.getCustomerId(), c));
        return p.map(r -> row(r, custMap));
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
