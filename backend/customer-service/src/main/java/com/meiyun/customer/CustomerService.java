package com.meiyun.customer;

import com.meiyun.security.DataScope;
import com.meiyun.security.SecurityContext;
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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CustomerService {

    /** 性别取值白名单（对齐 customer gender CHECK 约束）。 */
    private static final Set<String> GENDERS = Set.of("女", "男", "其他");
    /** 获客渠道白名单（对齐真实库 7 码 + CUSTOMER_SOURCE 字典）。 */
    private static final Set<String> CHANNELS =
            Set.of("WALK_IN", "REFERRAL", "WECHAT", "DOUYIN", "XIAOHONGSHU", "MEITUAN", "OTHER");
    /** 大陆手机号：1 开头、第二位 3-9、共 11 位数字。 */
    private static final Pattern PHONE_RE = Pattern.compile("^1[3-9]\\d{9}$");

    private final CustomerRepository customerRepo;
    private final MemberLevelRepository levelRepo;
    private final MemberCardRepository cardRepo;
    private final PointsLedgerRepository ledgerRepo;
    private final PointsPoolRepository pointsPoolRepo;
    private final CustomerTagRelRepository tagRelRepo;
    private final CustomerTagRepository tagRepo;
    private final RefNameResolver nameResolver;

    public CustomerService(CustomerRepository customerRepo, MemberLevelRepository levelRepo,
                           MemberCardRepository cardRepo,
                           PointsLedgerRepository ledgerRepo, PointsPoolRepository pointsPoolRepo,
                           CustomerTagRelRepository tagRelRepo, CustomerTagRepository tagRepo,
                           RefNameResolver nameResolver) {
        this.customerRepo = customerRepo;
        this.levelRepo = levelRepo;
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

    /**
     * 新建客户（写接口四件套：校验 / 防重 / 审计 / 中文错误）。
     * 编号由后端按库内最大 M 序号生成（synchronized + max 查询防重号，禁内存自增）；
     * 归属门店/归属人取登录上下文，前端不可伪造；status/points/消费等聚合字段一律服务端置默认。
     * 同门店同手机号撞单直接中文拒绝（避免重复建档）。审计由 Controller 落 CUSTOMER/CREATE。
     */
    @Transactional
    public synchronized Customer create(Customer input) {
        if (input == null) throw new BadReq("请求体不能为空");
        String name = trim(input.getName());
        String phone = trim(input.getPhone());
        String gender = trim(input.getGender());
        String level = trim(input.getLevel());
        String channel = trim(input.getChannel());

        if (name.isEmpty()) throw new BadReq("请填写客户姓名");
        if (name.length() > 32) throw new BadReq("客户姓名最长 32 字");
        if (phone.isEmpty()) throw new BadReq("请填写手机号");
        if (!PHONE_RE.matcher(phone).matches()) throw new BadReq("手机号格式不正确（需 11 位大陆手机号）");
        if (!GENDERS.contains(gender)) throw new BadReq("性别取值非法：女 / 男 / 其他");
        if (level.isEmpty()) level = "普通";
        if (!levelRepo.existsById(level)) throw new BadReq("会员等级不存在：" + level);
        if (channel.isEmpty()) channel = "WALK_IN";
        if (!CHANNELS.contains(channel)) throw new BadReq("获客渠道取值非法：" + channel);

        // 归属门店/归属人由登录上下文权威注入（SELF/STORE 有门店；GROUP/REGION 无门店则进公海）
        String storeCode = DataScope.current() != null ? trim(DataScope.current().storeCode()) : "";
        if (storeCode.isEmpty()) storeCode = null;
        String ownerStaffId = trim(SecurityContext.currentStaffId());
        if (ownerStaffId.isEmpty()) ownerStaffId = null;

        // 撞单：同门店同手机号拒绝（公海客户全局查重），提示已有客户号
        Optional<Customer> dup = storeCode != null
                ? customerRepo.findFirstByStoreCodeAndPhone(storeCode, phone)
                : customerRepo.findFirstByStoreCodeIsNullAndPhone(phone);
        if (dup.isPresent()) {
            throw new BadReq("该手机号已建档（客户号 " + dup.get().getCustomerId() + "），请勿重复新建");
        }

        Customer c = new Customer();
        c.setCustomerId(nextCustomerId());
        c.setName(name);
        c.setPhone(phone);
        c.setGender(gender);
        c.setLevel(level);
        c.setChannel(channel);
        c.setBirthDate(input.getBirthDate());
        c.setStoreCode(storeCode);
        c.setOwnerStaffId(ownerStaffId);
        // points/status/totalSpend/visitCount/createdAt 由 @PrePersist 置默认（0/活跃/0 元/0 次/当前时间）
        return customerRepo.save(c);
    }

    /** 生成下一个客户编号：M+3 位序号，基于库内最大号递增（synchronized 防并发重号）。 */
    private String nextCustomerId() {
        String max = customerRepo.maxMId();
        int seq = 0;
        if (max != null && max.startsWith("M")) {
            try {
                seq = Integer.parseInt(max.substring(1));
            } catch (NumberFormatException ignored) {
                seq = 0;
            }
        }
        return String.format("M%03d", seq + 1);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** 业务异常 → HTTP 状态码映射（由 GlobalExceptionHandler 处理）。 */
    public static class NotFound extends RuntimeException { public NotFound(String m){super(m);} }
    public static class BadReq extends RuntimeException { public BadReq(String m){super(m);} }
}
