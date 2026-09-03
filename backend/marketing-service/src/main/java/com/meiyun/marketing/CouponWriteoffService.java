package com.meiyun.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.SecurityContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 优惠券核销写链路（M5-12 扫码核销台）。
 *
 * <p>校验顺序：参数 → 券码存在（FORGED 伪造）→ 未重复核销（DUPLICATE 重复）→
 * 券进行中且在有效期内且库存未核完（EXPIRED 过期）。异常同样落流水供告警，
 * 仅 OK 回写券 usedQty 并全动作审计；抵扣口径：满减券需订单达门槛，折扣券按折扣×10 比率，
 * 券包为核销兑换项（discountFen=0）。金额一律「分」。
 */
@Service
public class CouponWriteoffService {

    private static final String DEFAULT_STORE_CODE = "SST01";
    private static final String CHANNEL = "门店核销";
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");

    private final CouponWriteoffRecordRepository writeoffRepo;
    private final CouponTemplateRepository couponRepo;
    private final BizNoGenerator noGen;
    private final AuditRecorder audit;
    private final StoreNameResolver storeNameResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CouponWriteoffService(CouponWriteoffRecordRepository writeoffRepo,
                                 CouponTemplateRepository couponRepo,
                                 BizNoGenerator noGen, AuditRecorder audit,
                                 StoreNameResolver storeNameResolver) {
        this.writeoffRepo = writeoffRepo;
        this.couponRepo = couponRepo;
        this.noGen = noGen;
        this.audit = audit;
        this.storeNameResolver = storeNameResolver;
    }

    public List<CouponWriteoffRecord> list() {
        return writeoffRepo.findAllByOrderByVerifiedAtDesc();
    }

    /**
     * 扫码核销。入参金额单位「分」；手机号可带掩码但真实核验需 11 位手机号。
     * 异常不抛 400（均落流水 + 返回 ok=false），仅参数非法抛 400 中文错误。
     */
    @Transactional
    public CouponWriteoffRecord verify(VerifyCmd cmd) {
        if (cmd == null || cmd.couponCode() == null || cmd.couponCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入或扫描券码");
        }
        if (cmd.customerName() == null || cmd.customerName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写客户姓名");
        }
        if (cmd.customerPhone() == null || cmd.customerPhone().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写客户手机号");
        }
        String phone = cmd.customerPhone().trim().replaceAll("[\\s-]", "");
        if (!PHONE.matcher(phone).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号格式不正确（需 11 位大陆手机号）");
        }
        if (cmd.orderAmountFen() == null || cmd.orderAmountFen() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "核销订单金额必须大于 0");
        }

        String code = cmd.couponCode().trim();
        Optional<CouponTemplate> hit = couponRepo.findByCouponCodeIgnoreCase(code);

        CouponWriteoffRecord rec = new CouponWriteoffRecord();
        rec.setWriteoffId(noGen.next("WR", like -> writeoffRepo
                .findTopByWriteoffIdLikeOrderByWriteoffIdDesc(like).map(CouponWriteoffRecord::getWriteoffId).orElse(null)));
        rec.setCouponCode(code);
        rec.setCouponName(hit.map(CouponTemplate::getCouponName).orElse("未知券"));
        rec.setCouponId(hit.map(CouponTemplate::getCouponId).orElse(null));
        rec.setCustomerName(cmd.customerName().trim());
        rec.setCustomerPhone(maskPhone(phone));
        rec.setStoreCode(DEFAULT_STORE_CODE);
        rec.setStoreName(resolveStoreName(DEFAULT_STORE_CODE));
        rec.setOrderAmountFen(cmd.orderAmountFen());
        rec.setDiscountFen(0L);
        rec.setChannel(CHANNEL);
        rec.setOperator(operatorName());
        rec.setVerifiedAt(java.time.OffsetDateTime.now());

        if (hit.isEmpty()) {
            rec.setStatus("FORGED");
            rec.setReason("券码不存在，疑似伪造");
            return saveAbnormal(rec);
        }
        CouponTemplate c = hit.get();
        if (writeoffRepo.existsByCouponCodeIgnoreCaseAndStatus(c.getCouponCode(), "OK")) {
            rec.setStatus("DUPLICATE");
            rec.setReason("该券已核销，禁止重复使用");
            return saveAbnormal(rec);
        }
        boolean expired = !"ACTIVE".equals(c.getStatus())
                || (c.getValidEnd() != null && c.getValidEnd().isBefore(LocalDate.now()))
                || (c.getValidStart() != null && c.getValidStart().isAfter(LocalDate.now()))
                || c.getUsedQty() >= c.getTotalQty();
        if (expired) {
            rec.setStatus("EXPIRED");
            rec.setReason("券已停用、过期或已用完，不可核销");
            return saveAbnormal(rec);
        }

        long discount = calcDiscount(c, cmd.orderAmountFen());
        rec.setDiscountFen(discount);
        rec.setStatus("OK");
        if (c.getThreshold() != null && c.getThreshold() > 0 && cmd.orderAmountFen() < c.getThreshold()
                && "AMOUNT".equals(c.getCouponType())) {
            rec.setReason("订单未达使用门槛 ¥" + fen2yuan(c.getThreshold()) + "，本次未抵扣");
        }
        c.setUsedQty(c.getUsedQty() + 1);
        couponRepo.save(c);
        CouponWriteoffRecord saved = writeoffRepo.save(rec);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("couponCode", c.getCouponCode());
        payload.put("couponName", c.getCouponName());
        payload.put("customerName", rec.getCustomerName());
        payload.put("customerPhone", rec.getCustomerPhone());
        payload.put("orderAmountFen", rec.getOrderAmountFen());
        payload.put("discountFen", discount);
        payload.put("usedQty", c.getUsedQty());
        payload.put("totalQty", c.getTotalQty());
        audit("WRITEOFF", saved.getWriteoffId(), payload);
        return saved;
    }

    private CouponWriteoffRecord saveAbnormal(CouponWriteoffRecord rec) {
        CouponWriteoffRecord saved = writeoffRepo.save(rec);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("couponCode", rec.getCouponCode());
        payload.put("couponName", rec.getCouponName());
        payload.put("customerName", rec.getCustomerName());
        payload.put("customerPhone", rec.getCustomerPhone());
        payload.put("orderAmountFen", rec.getOrderAmountFen());
        payload.put("status", rec.getStatus());
        payload.put("reason", rec.getReason());
        audit("BLOCK", saved.getWriteoffId(), payload);
        return saved;
    }

    /** 抵扣额（分）：满减券达门槛减面值；折扣券按面值/1000 比率（8.5 折存 85 → 减 15%）；券包为兑换项不抵扣。 */
    private long calcDiscount(CouponTemplate c, long orderAmountFen) {
        return switch (c.getCouponType()) {
            case "AMOUNT" -> {
                if (c.getThreshold() != null && orderAmountFen < c.getThreshold()) {
                    yield 0L;
                }
                yield Math.min(c.getFaceValue(), orderAmountFen);
            }
            case "RATE" -> {
                // faceValue = 折扣 × 10（8.5 折存 85）：顾客支付 faceValue/100，抵扣 = 金额 × (1 - faceValue/100)
                long pay = Math.round(orderAmountFen * (c.getFaceValue() / 100.0));
                yield Math.max(0L, Math.min(orderAmountFen - pay, orderAmountFen));
            }
            default -> 0L;
        };
    }

    private String resolveStoreName(String code) {
        String name = storeNameResolver.resolveNames(List.of(code)).get(code);
        return (name == null || name.isBlank()) ? code : name;
    }

    private String maskPhone(String phone) {
        return phone.length() == 11 ? phone.substring(0, 3) + "****" + phone.substring(7) : phone;
    }

    private String fen2yuan(long fen) {
        return String.valueOf(fen / 100);
    }

    private String operatorName() {
        String name = SecurityContext.currentStaffName();
        return (name == null || name.isBlank()) ? "前台" : name;
    }

    private void audit(String action, String txnNo, Map<String, Object> payload) {
        try {
            audit.record("COUPON_WRITEOFF", txnNo, DataScope.currentActor(), action,
                    objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            audit.record("COUPON_WRITEOFF", txnNo, DataScope.currentActor(), action, "{}");
        }
    }

    /** 核销命令。金额单位「分」。 */
    public record VerifyCmd(
            String couponCode,
            String customerName,
            String customerPhone,
            Long orderAmountFen) {}
}
