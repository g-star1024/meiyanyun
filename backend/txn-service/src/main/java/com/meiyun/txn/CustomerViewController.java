package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户 360 只读视图聚合（txn 域）：按客户查 订单 / 面诊咨询 / 预约。
 * 仅返回客户视图所需的精简字段，工号（consultant/doctor）经 TxnStaffNameResolver 解析为中文名；
 * 不下发签核/豁免签名、过敏史明细等敏感/内部字段。金额单位「分」，状态/来源为中文。
 */
@RestController
@RequestMapping("/api/txn/customer")
@RequirePerm("customer:view")
public class CustomerViewController {

    private final TxnOrderRepository orderRepo;
    private final OrderItemRepository itemRepo;
    private final ConsultationRepository consultRepo;
    private final AppointmentRepository apptRepo;
    private final TxnStaffNameResolver staffNames;
    private final RfmCalculator rfmCalculator;

    public CustomerViewController(TxnOrderRepository orderRepo, OrderItemRepository itemRepo,
                                  ConsultationRepository consultRepo, AppointmentRepository apptRepo,
                                  TxnStaffNameResolver staffNames, RfmCalculator rfmCalculator) {
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.consultRepo = consultRepo;
        this.apptRepo = apptRepo;
        this.staffNames = staffNames;
        this.rfmCalculator = rfmCalculator;
    }

    /** 订单收费子项（读模型）：项目名 + 数量 + 单价（分）+ 小计（分）。 */
    public record OrderItemView(String itemName, Integer qty, Long unitPrice, Long amount) {}

    public record OrderView(String orderNo, String project, Long amount, String status,
                            String consultantName, OffsetDateTime createdAt,
                            List<OrderItemView> items) {}

    public record ConsultView(String consultId, String skinStatus, String needs,
                              String consultantName, boolean privacyMasked, OffsetDateTime createdAt) {}

    public record ApptView(String apptNo, String project, LocalDate apptDate, String apptTime,
                           String doctorName, String source, String status) {}

    /**
     * 客户消费订单（时间倒序），每单附带收费子项明细（批量取，防 N+1）。
     * 数据域强制注入：SELF 咨询师只见本人订单、STORE 本店、REGION 本区、GROUP 全量。
     */
    @GetMapping("/{customerId}/orders")
    public List<OrderView> orders(@PathVariable String customerId) {
        Specification<TxnOrder> spec = DataScope.<TxnOrder>ownedSpec("storeCode", "consultant")
                .and((root, q, cb) -> cb.equal(root.get("customerId"), customerId));
        List<TxnOrder> orders = orderRepo.findAll(spec, Sort.by(Sort.Order.desc("createdAt")));
        Map<String, String> names = staffNames.staffNames(
                orders.stream().map(TxnOrder::getConsultant).toList());

        // 批量取全部订单的子项，按 orderNo 归组（子项按 line_no 排序）
        Map<String, List<OrderItemView>> itemsByOrder = new LinkedHashMap<>();
        if (!orders.isEmpty()) {
            List<String> orderNos = orders.stream().map(TxnOrder::getOrderNo).toList();
            itemRepo.findByOrderNoIn(orderNos).stream()
                    .sorted((a, b) -> Integer.compare(a.getLineNo(), b.getLineNo()))
                    .forEach(it -> itemsByOrder
                            .computeIfAbsent(it.getOrderNo(), k -> new ArrayList<>())
                            .add(new OrderItemView(it.getItemName(), it.getQty(), it.getUnitPrice(), it.getAmount())));
        }

        return orders.stream().map(o -> new OrderView(
                o.getOrderNo(), o.getProject(), o.getAmount(), o.getStatus(),
                names.get(o.getConsultant()), o.getCreatedAt(),
                itemsByOrder.getOrDefault(o.getOrderNo(), List.of()))).toList();
    }

    /**
     * 客户价值模型（RFM + 忠诚 + 活跃），读时实时计算、不落库。
     * 聚合该客户已收款订单即时打分，规则见 docs/RFM-RULES.md。会员存续月数属客户域，
     * 此处忠诚度按累计成交单量判定（tenureMonths 留空）。
     */
    @GetMapping("/{customerId}/rfm")
    public RfmCalculator.RfmView rfm(@PathVariable String customerId) {
        // 数据域与订单列表一致：仅统计当前登录人可见门店/本人归属的订单
        Specification<TxnOrder> spec = DataScope.<TxnOrder>ownedSpec("storeCode", "consultant")
                .and((root, q, cb) -> cb.equal(root.get("customerId"), customerId));
        List<TxnOrder> orders = orderRepo.findAll(spec, Sort.by(Sort.Order.desc("createdAt")));
        return rfmCalculator.calc(orders, null);
    }

    /** 客户面诊/咨询记录（时间倒序，概要字段；过敏史明细不在此下发）。数据域：SELF 只见本人客情。 */
    @GetMapping("/{customerId}/consultations")
    public List<ConsultView> consultations(@PathVariable String customerId) {
        Specification<Consultation> spec = DataScope.<Consultation>ownedSpec("storeCode", "consultant")
                .and((root, q, cb) -> cb.equal(root.get("customerId"), customerId));
        List<Consultation> list = consultRepo.findAll(spec, Sort.by(Sort.Order.desc("createdAt")));
        Map<String, String> names = staffNames.staffNames(
                list.stream().map(Consultation::getConsultant).toList());
        return list.stream().map(c -> new ConsultView(
                c.getConsultId(), c.getSkinStatus(), c.getNeeds(),
                names.get(c.getConsultant()),
                Boolean.TRUE.equals(c.getPrivacyMasked()), c.getCreatedAt())).toList();
    }

    /** 客户预约记录（近 365 天，日期倒序）。数据域：SELF 医生只见本人预约、STORE/REGION/GROUP 按门店域。 */
    @GetMapping("/{customerId}/appointments")
    public List<ApptView> appointments(@PathVariable String customerId) {
        Specification<Appointment> spec = DataScope.<Appointment>ownedSpec("storeCode", "doctor")
                .and((root, q, cb) -> cb.equal(root.get("customerId"), customerId))
                .and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("apptDate"),
                        LocalDate.now().minusDays(365)));
        List<Appointment> list = apptRepo.findAll(spec, Sort.by(Sort.Order.desc("apptDate")));
        Map<String, String> names = staffNames.staffNames(
                list.stream().map(Appointment::getDoctor).toList());
        return list.stream().map(a -> new ApptView(
                a.getApptNo(), a.getProject(), a.getApptDate(), a.getApptTime(),
                names.get(a.getDoctor()), a.getSource(), a.getStatus())).toList();
    }
}
