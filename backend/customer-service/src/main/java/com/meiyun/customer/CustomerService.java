package com.meiyun.customer;

import com.meiyun.security.DataScope;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CustomerService {

    private final CustomerRepository customerRepo;
    private final MemberCardRepository cardRepo;
    private final PointsLedgerRepository ledgerRepo;
    private final PointsPoolRepository pointsPoolRepo;
    private final CustomerTagRelRepository tagRelRepo;
    private final CustomerTagRepository tagRepo;
    private final RefNameResolver nameResolver;

    public CustomerService(CustomerRepository customerRepo, MemberCardRepository cardRepo,
                           PointsLedgerRepository ledgerRepo, PointsPoolRepository pointsPoolRepo,
                           CustomerTagRelRepository tagRelRepo, CustomerTagRepository tagRepo,
                           RefNameResolver nameResolver) {
        this.customerRepo = customerRepo;
        this.cardRepo = cardRepo;
        this.ledgerRepo = ledgerRepo;
        this.pointsPoolRepo = pointsPoolRepo;
        this.tagRelRepo = tagRelRepo;
        this.tagRepo = tagRepo;
        this.nameResolver = nameResolver;
    }

    public List<Customer> listCustomers(String storeCode, String level, String status) {
        if (level != null && storeCode != null) return customerRepo.findByLevelAndStoreCode(level, storeCode);
        if (storeCode != null) return customerRepo.findByStoreCode(storeCode);
        if (level != null) return customerRepo.findByLevel(level);
        if (status != null) return customerRepo.findByStatus(status);
        return customerRepo.findAll();
    }

    /**
     * 客户列表（分页 + 动态过滤 + 标签批量解析）。
     * 过滤：门店/等级/状态/来源/关键字（姓名或手机号模糊）。
     * 标签通过 findByCustomerIdIn 一次批量取出，避免 N+1。
     */
    public Page<CustomerRowDTO> listRows(Pageable pageable, String storeCode, String level,
                                         String status, String channel, String keyword) {
        // 数据域强制注入（服务端权威）：SELF 只见本人归属客户，STORE 本店，REGION 本区门店，GROUP/BRAND 全量；前端 storeCode 参数在域内收窄
        Specification<Customer> scopeSpec = DataScope.ownedSpec("storeCode", "ownerStaffId");
        // 手机号脱敏：无 customer:phone:decrypt 权限（或匿名服务间通道）仅见掩码；有权限见明文（列表仍受数据域约束）
        boolean showPhone = DataScope.hasPerm("customer:phone:decrypt");
        Specification<Customer> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (storeCode != null) ps.add(cb.equal(root.get("storeCode"), storeCode));
            if (level != null) ps.add(cb.equal(root.get("level"), level));
            if (status != null) ps.add(cb.equal(root.get("status"), status));
            if (channel != null) ps.add(cb.equal(root.get("channel"), channel));
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword + "%";
                // 支持客户编号（SC001）检索：触达等 B 端操作页常按编号定位客户
                ps.add(cb.or(cb.like(root.get("name"), like),
                             cb.like(root.get("phone"), like),
                             cb.like(root.get("customerId"), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };

        Page<Customer> page = customerRepo.findAll(scopeSpec.and(spec), pageable);

        List<String> ids = page.stream().map(Customer::getCustomerId).toList();
        Map<String, List<String>> tagsByCust = new HashMap<>();
        if (!ids.isEmpty()) {
            List<CustomerTagRel> rels = tagRelRepo.findByCustomerIdIn(ids);
            if (!rels.isEmpty()) {
                Map<String, String> nameById = tagRepo.findAllById(
                                rels.stream().map(CustomerTagRel::getTagId).collect(Collectors.toSet()))
                        .stream().collect(Collectors.toMap(CustomerTag::getTagId, CustomerTag::getTagName));
                for (CustomerTagRel r : rels) {
                    String nm = nameById.get(r.getTagId());
                    if (nm != null) tagsByCust.computeIfAbsent(r.getCustomerId(), k -> new ArrayList<>()).add(nm);
                }
            }
        }

        // 批量解析归属员工/门店中文名（同库只读，避免 N+1）
        Map<String, String> staffNames = nameResolver.staffNames(
                page.stream().map(Customer::getOwnerStaffId).toList());
        Map<String, String> storeNames = nameResolver.storeNames(
                page.stream().map(Customer::getStoreCode).toList());

        return page.map(c -> new CustomerRowDTO(
                c.getCustomerId(), c.getName(), maskPhone(c.getPhone(), showPhone), c.getGender(), c.getLevel(),
                c.getStoreCode(), storeNames.get(c.getStoreCode()),
                c.getChannel(), c.getTotalSpend(), c.getVisitCount(),
                c.getOwnerStaffId(), staffNames.get(c.getOwnerStaffId()),
                c.getStatus(), c.getPoints(),
                tagsByCust.getOrDefault(c.getCustomerId(), List.of())));
    }

    /** 客户详情：基础档案 + 归属员工/门店中文名。越权一律 404，不泄露数据是否存在。 */
    public CustomerDetailDTO getDetail(String customerId) {
        Customer c = customerRepo.findById(customerId)
                .orElseThrow(() -> new NotFound("数据不存在或无权查看"));
        if (!DataScope.canReadOwned(c.getStoreCode(), c.getOwnerStaffId())) {
            throw new NotFound("数据不存在或无权查看");
        }
        boolean showPhone = DataScope.hasPerm("customer:phone:decrypt");
        // 归属员工可能为空（公海客户）：singletonList 允许 null 元素，resolver 内部过滤 null
        Map<String, String> staffNames = nameResolver.staffNames(Collections.singletonList(c.getOwnerStaffId()));
        Map<String, String> storeNames = nameResolver.storeNames(Collections.singletonList(c.getStoreCode()));
        return new CustomerDetailDTO(
                c.getCustomerId(), c.getName(), maskPhone(c.getPhone(), showPhone), c.getGender(), c.getBirthDate(),
                c.getLevel(), c.getStoreCode(), storeNames.get(c.getStoreCode()),
                c.getChannel(), c.getTotalSpend(), c.getVisitCount(),
                c.getOwnerStaffId(), staffNames.get(c.getOwnerStaffId()),
                c.getStatus(), c.getPoints(), c.getCreatedAt());
    }

    /**
     * 手机号脱敏：无解密权限时 11 位手机号掩码为 138****8000（保留前 3 后 4）；
     * 非标准长度退化为尾 4 位掩码；null/空原样返回。
     */
    static String maskPhone(String phone, boolean showPlain) {
        if (phone == null || phone.isBlank() || showPlain) {
            return phone;
        }
        String p = phone.trim();
        if (p.length() == 11) {
            return p.substring(0, 3) + "****" + p.substring(7);
        }
        if (p.length() > 4) {
            return "****" + p.substring(p.length() - 4);
        }
        return "****";
    }

    public List<MemberCard> listCards(String customerId, boolean activeOnly) {
        if (activeOnly) return cardRepo.findByCustomerIdAndStatus(customerId, "在用");
        return cardRepo.findByCustomerId(customerId);
    }

    /**
     * 积分变更（append-only）：只插入流水台账，不改历史；
     * 同步更新 customer.points 终值与 points_pool 月度累计。
     */
    @Transactional
    public PointsLedger changePoints(String customerId, long changeAmt, String reason) {
        Customer c = customerRepo.findById(customerId)
                .orElseThrow(() -> new NotFound("客户不存在: " + customerId));
        long after = c.getPoints() + changeAmt;
        if (after < 0) {
            throw new BadReq("积分不足：当前 " + c.getPoints() + "，变更 " + changeAmt);
        }
        c.setPoints(after);
        customerRepo.save(c);

        // 同步聚合池：获得计入 gained_month，兑换（负）计入 redeemed_month
        PointsPool pool = pointsPoolRepo.findById(1).orElseGet(() -> {
            PointsPool p = new PointsPool();
            p.setPoolId(1);
            return p;
        });
        if (changeAmt > 0) {
            pool.setGainedMonth(pool.getGainedMonth() + changeAmt);
        } else {
            pool.setRedeemedMonth(pool.getRedeemedMonth() + Math.abs(changeAmt));
        }
        pointsPoolRepo.save(pool);

        PointsLedger log = new PointsLedger();
        log.setCustomerId(customerId);
        log.setChangeAmt(changeAmt);
        log.setBalanceAfter(after);
        log.setReason(reason);
        return ledgerRepo.save(log);
    }

    /** 业务异常 → HTTP 状态码映射（由 GlobalExceptionHandler 处理）。 */
    public static class NotFound extends RuntimeException { public NotFound(String m){super(m);} }
    public static class BadReq extends RuntimeException { public BadReq(String m){super(m);} }
}
