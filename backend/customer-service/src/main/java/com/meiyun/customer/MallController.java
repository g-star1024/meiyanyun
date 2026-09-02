package com.meiyun.customer;

import com.meiyun.security.RequirePerm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M3-20 积分商城 B 端管理后台。
 *
 * 定位：B 端配置商品/库存/积分定价/上下架 + 兑换审核（双签）；C 端兑换入口在小程序/App。
 * 红线：兑换审核须双签（店长初审 + 运营复核），两签不得同一人。
 */
@RestController
@RequestMapping("/api/customer/mall")
public class MallController {

    private final MallProductRepository productRepo;
    private final PointRuleRepository ruleRepo;
    private final MallExchangeRepository exchangeRepo;
    private final CustomerService customerService;
    private final AtomicLong seq = new AtomicLong(System.nanoTime() % 1_000_000);

    public MallController(MallProductRepository productRepo, PointRuleRepository ruleRepo,
                          MallExchangeRepository exchangeRepo, CustomerService customerService) {
        this.productRepo = productRepo;
        this.ruleRepo = ruleRepo;
        this.exchangeRepo = exchangeRepo;
        this.customerService = customerService;
    }

    // ==================== 商品管理 ====================

    @GetMapping("/products")
    @RequirePerm("points:view")
    public List<MallProduct> products(@RequestParam(required = false) String status) {
        return status == null ? productRepo.findAllByOrderByCreatedAtDesc()
                : productRepo.findByStatusOrderByPointsPriceAsc(status);
    }

    @PostMapping("/product")
    @RequirePerm("points:edit")
    public MallProduct createProduct(@RequestBody @Valid ProductCmd cmd) {
        MallProduct p = new MallProduct();
        p.setProductId(nextNo("MP"));
        p.setProductName(cmd.name());
        p.setProductType(cmd.type());
        p.setPointsPrice(cmd.pointsPrice());
        p.setStock(cmd.stock() == null ? 0 : cmd.stock());
        p.setStatus("已下架");
        p.setCover(cmd.cover());
        p.setCreatedAt(OffsetDateTime.now());
        return productRepo.save(p);
    }

    /** 上下架切换。 */
    @PostMapping("/product/{id}/toggle")
    @RequirePerm("points:edit")
    public MallProduct toggle(@PathVariable String id) {
        MallProduct p = productRepo.findById(id)
                .orElseThrow(() -> new CustomerService.NotFound("商品不存在: " + id));
        p.setStatus("已上架".equals(p.getStatus()) ? "已下架" : "已上架");
        return productRepo.save(p);
    }

    /** 调整库存 / 积分定价。 */
    @PostMapping("/product/{id}/adjust")
    @RequirePerm("points:edit")
    public MallProduct adjust(@PathVariable String id, @RequestBody AdjustCmd cmd) {
        MallProduct p = productRepo.findById(id)
                .orElseThrow(() -> new CustomerService.NotFound("商品不存在: " + id));
        if (cmd.stock() != null) {
            if (cmd.stock() < 0) throw new CustomerService.BadReq("库存不可为负");
            p.setStock(cmd.stock());
        }
        if (cmd.pointsPrice() != null) {
            if (cmd.pointsPrice() <= 0) throw new CustomerService.BadReq("积分定价须大于 0");
            p.setPointsPrice(cmd.pointsPrice());
        }
        return productRepo.save(p);
    }

    // ==================== 积分规则 ====================

    @GetMapping("/rule")
    public PointRule rule() {
        return ruleRepo.findById(1).orElseGet(PointRule::new);
    }

    // ==================== 兑换审核（双签） ====================

    @GetMapping("/exchanges")
    @RequirePerm("points:view")
    public List<MallExchange> exchanges(@RequestParam(required = false) String status) {
        return status == null ? exchangeRepo.findAllByOrderByCreatedAtDesc()
                : exchangeRepo.findByStatusOrderByCreatedAtDesc(status);
    }

    /**
     * C 端兑换入口：客户提交兑换申请（生成「待审核」单，不立即扣积分/库存，
     * 双签审核通过时同事务扣减——走 B 端 review 链）。
     * 红线：仅已上架商品可兑；库存须充足；数量须大于 0。
     */
    @PostMapping("/exchange")
    @Transactional
    public MallExchange placeExchange(@RequestBody @Valid PlaceExchangeCmd cmd) {
        MallProduct p = productRepo.findById(cmd.productId())
                .orElseThrow(() -> new CustomerService.NotFound("商品不存在: " + cmd.productId()));
        if (!"已上架".equals(p.getStatus())) {
            throw new CustomerService.BadReq("商品未上架，不可兑换");
        }
        int qty = cmd.qty() == null ? 1 : cmd.qty();
        if (qty <= 0) {
            throw new CustomerService.BadReq("兑换数量须大于 0");
        }
        if (p.getStock() < qty) {
            throw new CustomerService.BadReq("库存不足：剩余 " + p.getStock() + "，需 " + qty);
        }
        int pointsSpent = p.getPointsPrice() * qty;
        MallExchange e = new MallExchange();
        e.setExchangeId(nextNo("EX"));
        e.setProductId(cmd.productId());
        e.setCustomerId(cmd.customerId());
        e.setQty(qty);
        e.setPointsSpent(pointsSpent);
        e.setStatus("待审核");
        e.setCreatedAt(OffsetDateTime.now());
        return exchangeRepo.save(e);
    }

    /** C 端「我的兑换」：按客户查兑换记录及审核状态。 */
    @GetMapping("/exchanges/my")
    @RequirePerm({"points:view", "customer:view"})
    public List<MallExchange> myExchanges(@RequestParam String customerId) {
        return exchangeRepo.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    /**
     * 兑换审核双签：sign1 店长初审 + sign2 运营复核，两签不得同一人。
     * 通过后扣减库存 + 扣减客户积分（走 CustomerService.changePoints，append-only）。
     */
    @PostMapping("/exchange/{id}/review")
    @RequirePerm("points:approve")
    @Transactional
    public MallExchange review(@PathVariable String id, @RequestBody @Valid ReviewCmd cmd) {
        MallExchange e = exchangeRepo.findById(id)
                .orElseThrow(() -> new CustomerService.NotFound("兑换单不存在: " + id));
        if (!"待审核".equals(e.getStatus())) {
            throw new CustomerService.BadReq("兑换单状态须为「待审核」，当前: " + e.getStatus());
        }
        if (cmd.sign1() == null || cmd.sign2() == null) {
            throw new CustomerService.BadReq("兑换审核须双签");
        }
        if (cmd.sign1().equals(cmd.sign2())) {
            throw new CustomerService.BadReq("双签不得为同一人");
        }
        OffsetDateTime now = OffsetDateTime.now();
        e.setSign1(cmd.sign1()); e.setSign1Role(cmd.sign1Role()); e.setSignedAt1(now);
        e.setSign2(cmd.sign2()); e.setSign2Role(cmd.sign2Role()); e.setSignedAt2(now);

        if (Boolean.TRUE.equals(cmd.reject())) {
            e.setStatus("已拒绝");
            e.setRejectReason(cmd.rejectReason());
            return exchangeRepo.save(e);
        }

        // 通过：校验并扣减库存
        MallProduct p = productRepo.findById(e.getProductId())
                .orElseThrow(() -> new CustomerService.NotFound("商品不存在: " + e.getProductId()));
        if (p.getStock() < e.getQty()) {
            throw new CustomerService.BadReq("库存不足：剩余 " + p.getStock() + "，需 " + e.getQty());
        }
        p.setStock(p.getStock() - e.getQty());
        productRepo.save(p);

        // 扣减客户积分（append-only，积分不足抛 BadReq）
        customerService.changePoints(e.getCustomerId(), -e.getPointsSpent(),
                "积分商城兑换：" + p.getProductName());

        e.setStatus("已通过");
        return exchangeRepo.save(e);
    }

    // ==================== 内部方法 ====================

    private String nextNo(String prefix) {
        long n = seq.incrementAndGet() % 1_000_000;
        return prefix + "-" + String.format("%06d", n);
    }

    // ==================== 命令 DTO ====================

    public record ProductCmd(
            @NotBlank String name, @NotBlank String type,
            @NotNull Integer pointsPrice, Integer stock, String cover) {}

    public record AdjustCmd(Integer stock, Integer pointsPrice) {}

    public record ReviewCmd(
            String sign1, String sign1Role,
            String sign2, String sign2Role,
            Boolean reject, String rejectReason) {}

    /** C 端兑换申请：商品 + 客户 + 数量（积分按商品定价 × 数量后端计算，防止篡改）。 */
    public record PlaceExchangeCmd(
            @NotBlank String productId,
            @NotBlank String customerId,
            Integer qty) {}
}
