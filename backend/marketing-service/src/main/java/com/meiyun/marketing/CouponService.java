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
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 优惠券写链路：创建（草稿）/ 启用 / 停用 / 发放（防超发）。
 *
 * 状态机：DRAFT 草稿 → ACTIVE 进行中 → DISABLED 已停用（DRAFT/DISABLED 可再启用）；
 * EXPIRED 已过期由有效期日期在前端派生，不落库。
 * 金额口径：入参/落库均为「分」（元→分换算由前端适配层完成）；折扣券 faceValue = 折扣×10。
 * 防超发：发券方法 synchronized + 库存二次校验，实际发放 = min(申请数, 剩余库存)；库存为 0 直接 409。
 */
@Service
public class CouponService {

    public static final Set<String> TYPES = Set.of("AMOUNT", "RATE", "PACKAGE");
    public static final Set<String> SCOPES = Set.of("ALL", "NEW", "SEGMENT", "DESIGNATED");

    private final CouponTemplateRepository couponRepo;
    private final CouponGrantRepository grantRepo;
    private final BizNoGenerator noGen;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CouponService(CouponTemplateRepository couponRepo, CouponGrantRepository grantRepo,
                         BizNoGenerator noGen, AuditRecorder audit) {
        this.couponRepo = couponRepo;
        this.grantRepo = grantRepo;
        this.noGen = noGen;
        this.audit = audit;
    }

    // ==================== 查询 ====================

    public List<CouponTemplate> list() {
        return couponRepo.findAllByOrderByCreatedAtDesc();
    }

    public List<CouponGrant> listGrants() {
        return grantRepo.findAllByOrderByGrantedAtDesc();
    }

    // ==================== 写动作 ====================

    /** 创建券模板（落 DRAFT 草稿）。 */
    @Transactional
    public CouponTemplate create(CouponCmd cmd) {
        validate(cmd);
        CouponTemplate c = new CouponTemplate();
        c.setCouponId(noGen.next("CPN", like -> couponRepo
                .findTopByCouponIdLikeOrderByCouponIdDesc(like).map(CouponTemplate::getCouponId).orElse(null)));
        c.setCouponName(cmd.name().trim());
        c.setCouponType(cmd.type());
        c.setFaceValue(cmd.value());
        c.setThreshold(cmd.threshold() == null ? 0L : cmd.threshold());
        c.setTotalQty(cmd.total());
        c.setIssuedQty(0);
        c.setUsedQty(0);
        c.setStatus("DRAFT");
        c.setGrantScope(cmd.scope());
        c.setGrantScopeName(cmd.scopeName());
        c.setValidStart(cmd.startDate());
        c.setValidEnd(cmd.endDate());
        if (cmd.packageItems() != null && !cmd.packageItems().isBlank()) {
            verifyJsonArray(cmd.packageItems(), "券包子项");
            c.setPackageItems(cmd.packageItems());
        }
        c.setCreatedAt(OffsetDateTime.now());
        CouponTemplate saved = couponRepo.save(c);
        audit("CREATE", saved.getCouponId(), Map.of(
                "name", saved.getCouponName(), "type", saved.getCouponType(),
                "totalQty", saved.getTotalQty(), "status", saved.getStatus()));
        return saved;
    }

    /** 启用：DRAFT / DISABLED → ACTIVE。返回是否实际发生变更（幂等：已是 ACTIVE 返回 false）。 */
    @Transactional
    public boolean enable(String couponId) {
        CouponTemplate c = mustGet(couponId);
        if ("ACTIVE".equals(c.getStatus())) {
            return false;
        }
        if (!"DRAFT".equals(c.getStatus()) && !"DISABLED".equals(c.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "当前状态为「" + statusLabel(c.getStatus()) + "」的券不可启用，仅草稿/已停用可启用");
        }
        if (c.getValidStart() != null && c.getValidEnd() != null
                && c.getValidEnd().isBefore(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "券已过有效期，不可启用");
        }
        c.setStatus("ACTIVE");
        couponRepo.save(c);
        audit("ENABLE", couponId, Map.of("name", c.getCouponName(), "status", "ACTIVE"));
        return true;
    }

    /** 停用：ACTIVE → DISABLED。返回是否实际发生变更（幂等：已是 DISABLED 返回 false）。 */
    @Transactional
    public boolean disable(String couponId) {
        CouponTemplate c = mustGet(couponId);
        if ("DISABLED".equals(c.getStatus())) {
            return false;
        }
        if (!"ACTIVE".equals(c.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "当前状态为「" + statusLabel(c.getStatus()) + "」的券不可停用，仅进行中的券可停用");
        }
        c.setStatus("DISABLED");
        couponRepo.save(c);
        audit("DISABLE", couponId, Map.of("name", c.getCouponName(), "status", "DISABLED"));
        return true;
    }

    /**
     * 发券（防超发）：仅 ACTIVE 券可发放；实际发放 = min(申请数, 剩余库存)。
     * 库存为 0 → 409；部分发放正常落库（actual < reqCount 时审计/记录标注 partial=true）。
     */
    @Transactional
    public synchronized CouponGrant grant(String couponId, GrantCmd cmd) {
        CouponTemplate c = mustGet(couponId);
        if (!"ACTIVE".equals(c.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "仅进行中的券可发放，当前状态为「" + statusLabel(c.getStatus()) + "」");
        }
        if (cmd.count() == null || cmd.count() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发放数量必须大于 0");
        }
        if (cmd.scope() == null || !SCOPES.contains(cmd.scope())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发放范围不合法");
        }
        int left = c.getTotalQty() - c.getIssuedQty();
        if (left <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "券「" + c.getCouponName() + "」库存已发完（总量 " + c.getTotalQty() + "，已发 " + c.getIssuedQty() + "）");
        }
        int actual = Math.min(cmd.count(), left);
        c.setIssuedQty(c.getIssuedQty() + actual);
        couponRepo.save(c);

        CouponGrant g = new CouponGrant();
        g.setGrantId(noGen.next("GR", like -> grantRepo
                .findTopByGrantIdLikeOrderByGrantIdDesc(like).map(CouponGrant::getGrantId).orElse(null)));
        g.setCouponId(couponId);
        g.setCouponName(c.getCouponName());
        g.setGrantScope(cmd.scope());
        g.setTargetName(cmd.targetName() == null || cmd.targetName().isBlank() ? "—" : cmd.targetName().trim());
        g.setGrantCount(actual);
        g.setStatus("GRANTED");
        g.setGrantedAt(OffsetDateTime.now());
        g.setOperator(operatorName());
        CouponGrant saved = grantRepo.save(g);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", c.getCouponName());
        payload.put("scope", cmd.scope());
        payload.put("targetName", g.getTargetName());
        payload.put("reqCount", cmd.count());
        payload.put("actualCount", actual);
        payload.put("partial", actual < cmd.count());
        payload.put("issuedQty", c.getIssuedQty());
        payload.put("totalQty", c.getTotalQty());
        audit("GRANT", saved.getGrantId(), payload);
        return saved;
    }

    // ==================== 内部方法 ====================

    private void validate(CouponCmd cmd) {
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "券名称不可为空");
        }
        if (cmd.type() == null || !TYPES.contains(cmd.type())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "券类型不合法（AMOUNT/RATE/PACKAGE）");
        }
        if (cmd.total() == null || cmd.total() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发放库存必须大于 0");
        }
        if (cmd.value() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "券面值/折扣不可为空");
        }
        if ("AMOUNT".equals(cmd.type()) && cmd.value() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "满减金额必须大于 0");
        }
        if ("RATE".equals(cmd.type()) && (cmd.value() <= 0 || cmd.value() >= 1000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "折扣率不合法（如 8.5 折传 85，取值 1-999）");
        }
        if ("PACKAGE".equals(cmd.type()) && (cmd.packageItems() == null || cmd.packageItems().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "券包必须包含子项");
        }
        if (cmd.threshold() != null && cmd.threshold() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "使用门槛不可为负");
        }
        if (cmd.scope() == null || !SCOPES.contains(cmd.scope())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发放范围不合法（ALL/NEW/SEGMENT/DESIGNATED）");
        }
        if (cmd.startDate() != null && cmd.endDate() != null && cmd.endDate().isBefore(cmd.startDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "有效期结束日期不可早于开始日期");
        }
    }

    private void verifyJsonArray(String json, String field) {
        try {
            objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "不是合法的 JSON");
        }
    }

    private CouponTemplate mustGet(String couponId) {
        return couponRepo.findById(couponId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "券不存在：" + couponId));
    }

    private String operatorName() {
        String name = SecurityContext.currentStaffName();
        return (name == null || name.isBlank()) ? "系统" : name;
    }

    private void audit(String action, String txnNo, Map<String, Object> payload) {
        try {
            audit.record("COUPON", txnNo, DataScope.currentActor(), action, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            audit.record("COUPON", txnNo, DataScope.currentActor(), action, "{}");
        }
    }

    static String statusLabel(String status) {
        return switch (status) {
            case "DRAFT" -> "草稿";
            case "ACTIVE" -> "进行中";
            case "EXPIRED" -> "已过期";
            case "DISABLED" -> "已停用";
            default -> status;
        };
    }

    // ==================== 命令 DTO ====================

    /**
     * 创建券命令。金额单位「分」；折扣券 value = 折扣×10（如 8.5 折传 85）；
     * packageItems 为 JSON 数组文本（元数据，value 单位分）。
     */
    public record CouponCmd(
            String name,
            String type,
            Long value,
            Long threshold,
            Integer total,
            String scope,
            String scopeName,
            LocalDate startDate,
            LocalDate endDate,
            String packageItems) {}

    /** 发券命令。 */
    public record GrantCmd(
            String scope,
            String targetName,
            Integer count) {}
}
