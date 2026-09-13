package com.meiyun.customer;

import com.meiyun.security.RequirePerm;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 服务间内部端点：AI 客户画像上下文投影（B47 卡1，供 ai-service 组装画像 prompt 与 KPI）。
 *
 * <p>红线边界：画像上下文含累计消费/到店频次/诉求/意向等经营敏感字段，仅以系统身份
 * （X-Internal-Token，perms=["*"]，持 {@code internal:customer-directory}）开放；
 * 手机号在出域前一律掩码（138****8000），AI 域不接触明文手机号。AI 域不直读 customer 表，
 * 由客户域按自身实体语义提供投影，拆库后零改动成立。任一调用失败由 ai 侧降级，不阻断画像主流程。
 */
@RestController
@RequestMapping("/api/customer/internal")
public class InternalProfileController {

    private static final int SEARCH_LIMIT = 10;

    private final CustomerRepository customerRepo;
    private final CustomerTagRelRepository tagRelRepo;
    private final CustomerTagRepository tagRepo;

    public InternalProfileController(CustomerRepository customerRepo,
                                     CustomerTagRelRepository tagRelRepo,
                                     CustomerTagRepository tagRepo) {
        this.customerRepo = customerRepo;
        this.tagRelRepo = tagRelRepo;
        this.tagRepo = tagRepo;
    }

    /**
     * 单客户画像上下文：GET /api/customer/internal/profile-context/{customerId}。
     * 回档案基线（等级/渠道/消费/到店/肤质/诉求/意向）+ 掩码手机号 + 标签名清单；客户不存在 404 中文。
     */
    @GetMapping("/profile-context/{customerId}")
    @RequirePerm("internal:customer-directory")
    public ProfileContextDTO profileContext(@PathVariable("customerId") String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new CardLedgerService.BadReq("客户ID不能为空");
        }
        Customer c = customerRepo.findById(customerId.trim())
                .orElseThrow(() -> new CardLedgerService.NotFound("客户不存在: " + customerId));
        return toDto(c, tagsOf(Set.of(c.getCustomerId())));
    }

    /**
     * 画像搜索候选：GET /api/customer/internal/profile-context/search?keyword=张敏。
     * 按姓名/手机号/客户编号模糊取最近 10 个客户的画像上下文；关键词为空回空数组（由 AI 侧引导输入）。
     */
    @GetMapping("/profile-context/search")
    @RequirePerm("internal:customer-directory")
    public List<ProfileContextDTO> search(@RequestParam("keyword") String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String kw = keyword.trim();
        if (kw.length() > 64) {
            throw new CardLedgerService.BadReq("搜索关键词不能超过 64 字");
        }
        List<Customer> candidates = customerRepo.searchProfileCandidates(
                "%" + kw + "%", PageRequest.of(0, SEARCH_LIMIT));
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> tagsByCust = tagsOf(
                candidates.stream().map(Customer::getCustomerId).collect(Collectors.toSet()));
        return candidates.stream().map(c -> toDto(c, tagsByCust)).toList();
    }

    /**
     * AI 画像 KPI：GET /api/customer/internal/profile-metrics。
     * 回客户域全量客户数（覆盖客户）与标签库标签总数（标签数），派生统计不入库、不造数。
     */
    @GetMapping("/profile-metrics")
    @RequirePerm("internal:customer-directory")
    public Map<String, Object> metrics() {
        Map<String, Object> m = new HashMap<>();
        m.put("coveredCustomers", customerRepo.countAllCustomers());
        m.put("tagTotal", tagRepo.count());
        return m;
    }

    private Map<String, List<String>> tagsOf(Set<String> customerIds) {
        Map<String, List<String>> tagsByCust = new HashMap<>();
        if (customerIds.isEmpty()) {
            return tagsByCust;
        }
        List<CustomerTagRel> rels = tagRelRepo.findByCustomerIdIn(new ArrayList<>(customerIds));
        if (rels.isEmpty()) {
            return tagsByCust;
        }
        Map<String, String> nameById = tagRepo.findAllById(
                        rels.stream().map(CustomerTagRel::getTagId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(CustomerTag::getTagId, CustomerTag::getTagName));
        for (CustomerTagRel r : rels) {
            String name = nameById.get(r.getTagId());
            if (name != null) {
                tagsByCust.computeIfAbsent(r.getCustomerId(), k -> new ArrayList<>()).add(name);
            }
        }
        return tagsByCust;
    }

    private ProfileContextDTO toDto(Customer c, Map<String, List<String>> tagsByCust) {
        String phoneMask = CustomerService.maskPhone(c.getPhone(), false);
        return new ProfileContextDTO(
                c.getCustomerId(), c.getName(), phoneMask,
                c.getGender() == null ? "" : c.getGender(),
                c.getLevel() == null ? "普通" : c.getLevel(),
                c.getStoreCode() == null ? "" : c.getStoreCode(),
                c.getChannel() == null ? "" : c.getChannel(),
                c.getTotalSpend() == null ? BigDecimal.ZERO : c.getTotalSpend(),
                c.getVisitCount() == null ? 0 : c.getVisitCount(),
                c.getStatus() == null ? "" : c.getStatus(),
                c.getPoints() == null ? 0L : c.getPoints(),
                c.getAge(),
                c.getSkinType() == null ? "" : c.getSkinType(),
                c.getConcerns() == null ? List.of() : c.getConcerns(),
                c.getIntentProjects() == null ? List.of() : c.getIntentProjects(),
                c.getIntentLevel() == null ? "" : c.getIntentLevel(),
                c.getBudget() == null ? "" : c.getBudget(),
                tagsByCust.getOrDefault(c.getCustomerId(), List.of()));
    }

    /**
     * 画像上下文投影：档案基线 + 经营字段 + 标签名。手机号出域前已掩码。
     */
    public record ProfileContextDTO(String customerId, String name, String phone, String gender,
                                    String level, String storeCode, String channel,
                                    BigDecimal totalSpend, Integer visitCount, String status,
                                    Long points, Integer age, String skinType,
                                    List<String> concerns, List<String> intentProjects,
                                    String intentLevel, String budget, List<String> tags) {
    }
}
