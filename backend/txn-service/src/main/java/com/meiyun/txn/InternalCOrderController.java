package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import com.meiyun.txn.audit.AuditRecorder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * C 端订单 internal 端点（DESIGN-C §四端点 #7 落库通道，C-B4）：C 端会员下单经本端点
 * 落 B 端 txn_order + order_item 两表，与收银台/订单页/财务域天然同账（对账零适配）。
 *
 * <p>仅供 c-service 经 X-Internal-Token 系统身份调用（网关 withInternalGuard 对 /api/*&#8203;/internal/**
 * 外部 404 隐身，网关零改动）。定价口径：项目名/单价（分）由 c-service 侧 PRICELIST_SQL
 * （store_price ACTIVE × product_sku，promo??member??original 与 C 端价目页同源）解析后传入——
 * 本域守「禁 JdbcTemplate 跨域直读」铁律不自查价目表，硬校验正数与引用存在性；
 * 「用户所见价 = 落库价」由 c-service 同源 SQL 保证。
 *
 * <p>与 B 端差异：①B 端数据域（DataScope.canReadStore/ownedSpec）在此不适用——C 端行级隔离
 * 由 c-service 侧 customer_id 归属校验负责（越权 403 中文）；②customerId 强制非空（C 端
 * 下单必绑会员档案）；③状态直接落「待收款」——C 端先下单后支付/到店收银，无 B 端签核流，
 * 显式覆盖 {@link TxnOrder#prePersist} 默认「待签核」；④sourceType 固定 C_MINIAPP；
 * ⑤B62 会员折扣快照组/B73 售卡快照组对 C 端零售单不适用，留空；⑥审计 actor 固定
 * "c-service"（溯源通道），payload 带 channel:C。
 */
@RestController
@RequestMapping("/api/txn/internal/c-orders")
public class InternalCOrderController {

    /** C 端订单来源标识（txn_order.source_type 列，与 LIVE_SESSION/SHORT_VIDEO 同维度）。 */
    private static final String SOURCE_C_MINIAPP = "C_MINIAPP";
    /** C 端订单落库态：先下单后支付/到店收银，无签核流。 */
    private static final String ST_PENDING_PAY = "待收款";
    /** 单笔购买数量上限（防御性，C 端零售单正常为 1）。 */
    private static final int MAX_QTY = 99;

    private final TxnOrderRepository orderRepo;
    private final OrderItemRepository itemRepo;
    private final OrderNoGenerator orderNoGenerator;
    private final AuditRecorder audit;
    private final ApptRefNameResolver names;

    public InternalCOrderController(TxnOrderRepository orderRepo, OrderItemRepository itemRepo,
                                    OrderNoGenerator orderNoGenerator, AuditRecorder audit,
                                    ApptRefNameResolver names) {
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.orderNoGenerator = orderNoGenerator;
        this.audit = audit;
        this.names = names;
    }

    /** C 端下单命令（c-service 已解析门店编码/项目名/单价分，本侧硬校验存在性与正数）。 */
    public record CreateCOrderCmd(String customerId, String storeCode, String skuCode,
                                  String projectName, Long unitPriceFen, Integer qty) {
    }

    /** C 端订单视图（internal 响应；金额单位分，元换算与状态映射归 c-service 适配层）。 */
    public record COrderView(String orderNo, String customerId, String storeCode, String storeName,
                             String project, String skuCode, int qty, long amountFen,
                             String status, String createdAt) {
    }

    /** C 端创建订单：全量校验 → txn_order（待收款/C_MINIAPP）+ order_item（单行）→ 审计 ORDER/CREATE。 */
    @PostMapping
    @RequirePerm("internal:c-order")
    @Transactional
    public COrderView create(@RequestBody CreateCOrderCmd cmd) {
        if (cmd == null) {
            throw badRequest("请求体不能为空");
        }
        String customerId = cmd.customerId() == null ? "" : cmd.customerId().trim();
        if (customerId.isEmpty()) {
            throw badRequest("客户编号不能为空");
        }
        if (!names.customerNames(List.of(customerId)).containsKey(customerId)) {
            throw badRequest("客户不存在: " + customerId);
        }
        String storeCode = cmd.storeCode() == null ? "" : cmd.storeCode().trim();
        if (storeCode.isEmpty()) {
            throw badRequest("门店编码不能为空");
        }
        String storeName = names.storeNames(List.of(storeCode)).get(storeCode);
        if (storeName == null) {
            throw badRequest("门店不存在: " + storeCode);
        }
        String skuCode = cmd.skuCode() == null ? "" : cmd.skuCode().trim();
        if (skuCode.isEmpty()) {
            throw badRequest("项目编码不能为空");
        }
        if (skuCode.length() > 32) {
            throw badRequest("项目编码长度超限: " + skuCode);
        }
        String projectName = cmd.projectName() == null ? "" : cmd.projectName().trim();
        if (projectName.isEmpty()) {
            throw badRequest("项目名称不能为空");
        }
        if (projectName.length() > 64) {
            throw badRequest("项目名称长度超限");
        }
        if (cmd.unitPriceFen() == null || cmd.unitPriceFen() <= 0) {
            throw badRequest("项目单价必须为正数（单位分）");
        }
        if (cmd.qty() == null || cmd.qty() < 1) {
            throw badRequest("购买数量必须为正整数");
        }
        if (cmd.qty() > MAX_QTY) {
            throw badRequest("单笔订单购买数量不能超过 " + MAX_QTY);
        }
        long unitPrice = cmd.unitPriceFen();
        int qty = cmd.qty();
        long amount = unitPrice * qty;

        TxnOrder o = new TxnOrder();
        o.setOrderNo(orderNoGenerator.nextOrderNo());
        o.setCustomerId(customerId);
        o.setStoreCode(storeCode);
        o.setProject(projectName);
        o.setAmount(amount);
        o.setStatus(ST_PENDING_PAY);
        o.setSourceType(SOURCE_C_MINIAPP);
        o.setProductCode(skuCode);
        TxnOrder saved = orderRepo.save(o);

        OrderItem item = new OrderItem();
        item.setOrderNo(saved.getOrderNo());
        item.setLineNo(1);
        item.setItemName(projectName);
        item.setQty(qty);
        item.setUnitPrice(unitPrice);
        item.setAmount(amount);
        itemRepo.save(item);

        audit.record("ORDER", saved.getOrderNo(), "c-service", "CREATE",
                "{\"customerId\":\"" + customerId + "\",\"storeCode\":\"" + storeCode
                        + "\",\"skuCode\":\"" + skuCode + "\",\"qty\":" + qty
                        + ",\"amountFen\":" + amount + ",\"channel\":\"C\"}");

        return new COrderView(saved.getOrderNo(), customerId, storeCode, storeName,
                projectName, skuCode, qty, amount, saved.getStatus(),
                String.valueOf(saved.getCreatedAt()));
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
