package com.meiyun.customer;

import com.meiyun.customer.search.CustomerSearchService;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customer")
public class CustomerController {

    private final CustomerService service;
    private final CustomerRepository customerRepo;
    private final MemberLevelRepository levelRepo;
    private final MemberCardRepository cardRepo;
    private final PointsLedgerRepository ledgerRepo;
    private final PointsPoolRepository poolRepo;
    private final CustomerTagRepository tagRepo;
    private final CustomerTagRelRepository tagRelRepo;
    private final CustomerSearchService searchService;

    public CustomerController(CustomerService service, CustomerRepository customerRepo,
                              MemberLevelRepository levelRepo, MemberCardRepository cardRepo,
                              PointsLedgerRepository ledgerRepo, PointsPoolRepository poolRepo,
                              CustomerTagRepository tagRepo, CustomerTagRelRepository tagRelRepo,
                              CustomerSearchService searchService) {
        this.service = service;
        this.customerRepo = customerRepo;
        this.levelRepo = levelRepo;
        this.cardRepo = cardRepo;
        this.ledgerRepo = ledgerRepo;
        this.poolRepo = poolRepo;
        this.tagRepo = tagRepo;
        this.tagRelRepo = tagRelRepo;
        this.searchService = searchService;
    }

    // ---- 客户主数据（分页 + 过滤 + 标签） ----
    @GetMapping
    @RequirePerm("customer:view")
    public Page<CustomerRowDTO> list(
            @PageableDefault(size = 100, sort = "createdAt") Pageable pageable,
            @RequestParam(required = false) String storeCode,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String keyword) {
        return service.listRows(pageable, storeCode, level, status, channel, keyword);
    }

    @GetMapping("/{id}")
    @RequirePerm("customer:view")
    public CustomerDetailDTO get(@PathVariable String id) {
        return service.getDetail(id);
    }

    @PostMapping
    @RequirePerm("customer:create")
    public Customer create(@RequestBody Customer c) {
        return customerRepo.save(c);
    }

    // ---- 会员等级聚合（ID-5：五级合计 48,600） ----
    @GetMapping("/member-levels")
    @RequirePerm("customer:view")
    public List<MemberLevel> levels() {
        return levelRepo.findAll();
    }

    // ---- 会员卡项 ----
    @GetMapping("/{id}/cards")
    @RequirePerm("customer:view")
    public List<MemberCard> cards(@PathVariable String id,
                                  @RequestParam(defaultValue = "false") boolean activeOnly) {
        requireReadable(id);
        return service.listCards(id, activeOnly);
    }

    // ---- 积分：台账 + 池 + 变更 ----
    @GetMapping("/{id}/points")
    @RequirePerm("points:view")
    public List<PointsLedger> pointsLog(@PathVariable String id) {
        requireReadable(id);
        return ledgerRepo.findByCustomerIdOrderByLedgerIdAsc(id);
    }

    @PostMapping("/{id}/points")
    @RequirePerm("points:edit")
    public PointsLedger changePoints(@PathVariable String id, @RequestBody PointsChangeReq req) {
        requireReadable(id);
        return service.changePoints(id, req.changeAmt(), req.reason());
    }

    @GetMapping("/points-pool")
    @RequirePerm({"points:view", "customer:view"})
    public PointsPool pointsPool() {
        return poolRepo.findById(1)
                .orElseThrow(() -> new CustomerService.NotFound("积分池未初始化"));
    }

    // ---- 标签 ----
    @GetMapping("/tags")
    @RequirePerm({"tag:view", "customer:view"})
    public List<CustomerTag> tags() { return tagRepo.findAll(); }

    @GetMapping("/{id}/tags")
    @RequirePerm({"tag:view", "customer:view"})
    public List<CustomerTagRel> customerTags(@PathVariable String id) {
        requireReadable(id);
        return tagRelRepo.findByCustomerId(id);
    }

    @PostMapping("/{id}/tags/{tagId}")
    @RequirePerm("tag:edit")
    public CustomerTagRel addTag(@PathVariable String id, @PathVariable String tagId) {
        requireReadable(id);
        tagRepo.findById(tagId)
                .orElseThrow(() -> new CustomerService.NotFound("标签不存在: " + tagId));
        CustomerTagRel rel = new CustomerTagRel();
        rel.setCustomerId(id);
        rel.setTagId(tagId);
        return tagRelRepo.save(rel);
    }

    /**
     * 批量客户名解析：GET /api/customer/name-map?ids=SC001&ids=SC002 → {"SC001":"王女士"}。
     * 供交易域（预约/订单列表）服务间调用富化客户名，缺失的 id 不留 key。
     * 注：精确路径 /name-map 优先于 /{id} 匹配，不会被当成客户号。
     */
    @GetMapping("/name-map")
    public Map<String, String> nameMap(@RequestParam("ids") List<String> ids) {
        List<String> distinct = ids.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        Map<String, String> m = new LinkedHashMap<>();
        if (distinct.isEmpty()) return m;
        for (Customer c : customerRepo.findAllById(distinct)) {
            if (c.getName() != null) m.put(c.getCustomerId(), c.getName());
        }
        return m;
    }

    /**
     * 批量客户掩码手机号解析：GET /api/customer/phone-map?ids=SC001 → {"SC001":"138****2046"}。
     * 手机号属敏感字段：无条件返回掩码（不开放明文），且仅服务间内部身份（X-Internal-Token）可调用；
     * 普通登录人即便带 token 也无 internal:phone-map 权限 → 403。缺失的 id 不留 key。
     */
    @GetMapping("/phone-map")
    @RequirePerm("internal:phone-map")
    public Map<String, String> phoneMap(@RequestParam("ids") List<String> ids) {
        List<String> distinct = ids.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        Map<String, String> m = new LinkedHashMap<>();
        if (distinct.isEmpty()) return m;
        for (Customer c : customerRepo.findAllById(distinct)) {
            if (c.getPhone() != null && !c.getPhone().isBlank()) {
                m.put(c.getCustomerId(), CustomerService.maskPhone(c.getPhone(), false));
            }
        }
        return m;
    }

    // ---- 客户全文检索（ES，ES 不可用降级 DB 内存过滤） ----
    @GetMapping("/search")
    @RequirePerm("customer:view")
    public List<Customer> search(@RequestParam String q) {
        List<String> ids = searchService.search(q);
        return customerRepo.findAllById(ids).stream()
                .filter(c -> DataScope.canReadOwned(c.getStoreCode(), c.getOwnerStaffId()))
                .toList();
    }

    /** 客户数据域断言：不存在或越权统一 404，不泄露数据是否存在。 */
    private Customer requireReadable(String id) {
        Customer c = customerRepo.findById(id)
                .orElseThrow(() -> new CustomerService.NotFound("数据不存在或无权查看"));
        if (!DataScope.canReadOwned(c.getStoreCode(), c.getOwnerStaffId())) {
            throw new CustomerService.NotFound("数据不存在或无权查看");
        }
        return c;
    }

    /** 全量重建 ES 客户索引（运维/初始化用）。 */
    @PostMapping("/search/reindex")
    @RequirePerm("aiAdmin:edit")
    public Map<String, Object> reindex() {
        int n = searchService.reindexAll();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("indexed", n);
        m.put("index", "meiyun-customer");
        return m;
    }

    public record PointsChangeReq(Long changeAmt, String reason) {}
}
