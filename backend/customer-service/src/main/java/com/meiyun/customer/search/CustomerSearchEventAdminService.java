package com.meiyun.customer.search;

import com.meiyun.customer.Customer;
import com.meiyun.customer.CustomerRepository;
import com.meiyun.customer.CustomerSearchEvent;
import com.meiyun.customer.CustomerSearchEventRepository;
import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.customer.search.CustomerSearchService.UpsertResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户检索事件处置台（B32）——把 B30 outbox 的 DEAD 事件从「只进不出」黑盒收口为可查可处置的运维台账。
 *
 * <p>动作集（权限码 customer:search:admin，仅区域经理/超管）：
 * <ul>
 *   <li>retry：DEAD → PENDING 交回 10 秒中继，异步重试（客户仍存在但 ES 曾 4xx/超 20 次场景）；</li>
 *   <li>replay：立即回查 PG 同步 upsert，当场拿到 SENT/RETRY/DEAD 结果，省去等待中继；</li>
 *   <li>discard：DEAD → DISCARDED 终态（客户已物理删除等永久毒消息），必填原因；</li>
 *   <li>reindex：全量重建（端点复用，本服务另记审计）。</li>
 * </ul>
 *
 * <p>事件表本身无门店列（只有 customerId），处置台为区域/集团级全局视图；列表回查 PG 富化客户名/门店仅供展示。
 * 所有写动作四件套：状态守卫（中文 400）/同态幂等重放/全动作审计（合法 JSON payload）/操作人取登录上下文。
 */
@Service
public class CustomerSearchEventAdminService {

    private static final Logger log = LoggerFactory.getLogger(CustomerSearchEventAdminService.class);

    public static final String ST_PENDING = "PENDING";
    public static final String ST_SENT = "SENT";
    public static final String ST_DEAD = "DEAD";
    public static final String ST_DISCARDED = "DISCARDED";

    private static final String BIZ_TYPE = "CUSTOMER_SEARCH_EVENT";

    private final CustomerSearchEventRepository eventRepo;
    private final CustomerRepository customerRepo;
    private final CustomerSearchService searchService;
    private final AuditRecorder audit;

    public CustomerSearchEventAdminService(CustomerSearchEventRepository eventRepo,
                                           CustomerRepository customerRepo,
                                           CustomerSearchService searchService,
                                           AuditRecorder audit) {
        this.eventRepo = eventRepo;
        this.customerRepo = customerRepo;
        this.searchService = searchService;
        this.audit = audit;
    }

    /** 分页清单（status/customerId/eventId 过滤，eventId 倒序），每页行富化客户名/手机号/门店。 */
    public Page<Map<String, Object>> list(Pageable pageable, String status, String customerId, Long eventId) {
        Pageable p = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                pageable.getSort().isSorted() ? pageable.getSort() : Sort.by(Sort.Order.desc("eventId")));
        Specification<CustomerSearchEvent> spec = (root, q, cb) -> cb.conjunction();
        if (status != null && !status.isBlank()) {
            String s = status.trim();
            if (!s.equals(ST_PENDING) && !s.equals(ST_SENT) && !s.equals(ST_DEAD) && !s.equals(ST_DISCARDED)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的事件状态：" + s);
            }
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), s));
        }
        if (customerId != null && !customerId.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("customerId"), customerId.trim()));
        }
        if (eventId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("eventId"), eventId));
        }
        return eventRepo.findAll(spec, p).map(this::toRow);
    }

    /** 状态 KPI：四态计数。 */
    public Map<String, Long> stats() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("pending", eventRepo.countByStatus(ST_PENDING));
        m.put("sent", eventRepo.countByStatus(ST_SENT));
        m.put("dead", eventRepo.countByStatus(ST_DEAD));
        m.put("discarded", eventRepo.countByStatus(ST_DISCARDED));
        return m;
    }

    /** 人工重试：DEAD → PENDING，retryCount 归零交回中继；已 PENDING 幂等返回。 */
    @Transactional
    public Map<String, Object> retry(Long eventId, String operator) {
        CustomerSearchEvent e = requireEvent(eventId);
        if (ST_PENDING.equals(e.getStatus())) {
            return toRow(e);
        }
        if (ST_SENT.equals(e.getStatus()) || ST_DISCARDED.equals(e.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该事件已是「" + statusLabel(e.getStatus()) + "」状态，无需重试（eventId=" + eventId + "）");
        }
        e.setStatus(ST_PENDING);
        e.setRetryCount(0);
        e.setLastError(null);
        e.setResolvedAt(OffsetDateTime.now());
        e.setResolvedBy(operator);
        e.setResolveNote(null);
        CustomerSearchEvent saved = eventRepo.save(e);
        log.info("检索事件人工重试回队 eventId={} customerId={} operator={}", eventId, e.getCustomerId(), operator);
        audit(operator, "MANUAL_RETRY", saved, "\"to\":\"PENDING\"}");
        return toRow(saved);
    }

    /**
     * 立即重放：回查 PG 后同步 upsert，当场回写结果。
     * 客户不存在→DEAD（与中继同口径）；SENT→置 SENT；RETRY→保留 DEAD 并提示稍后改用重试；DEAD→保持 DEAD。
     */
    @Transactional
    public Map<String, Object> replay(Long eventId, String operator) {
        CustomerSearchEvent e = requireEvent(eventId);
        if (ST_SENT.equals(e.getStatus()) || ST_DISCARDED.equals(e.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该事件已是「" + statusLabel(e.getStatus()) + "」状态，不可重放（eventId=" + eventId + "）");
        }
        Customer c = customerRepo.findById(e.getCustomerId()).orElse(null);
        OffsetDateTime now = OffsetDateTime.now();
        String result;
        if (c == null) {
            e.setStatus(ST_DEAD);
            e.setLastError(clip("客户不存在：" + e.getCustomerId(), 500));
            result = "DEAD";
        } else {
            UpsertResult r = searchService.upsert(c);
            result = r.name();
            switch (r) {
                case SENT -> {
                    e.setStatus(ST_SENT);
                    e.setSentAt(now);
                    e.setLastError(null);
                }
                case RETRY -> {
                    e.setRetryCount(e.getRetryCount() + 1);
                    e.setLastError(clip("人工重放时 ES 暂不可用，可稍后重试", 500));
                }
                case DEAD -> e.setLastError(clip("ES 确定性拒绝（4xx），停止自动投递", 500));
            }
        }
        e.setResolvedAt(now);
        e.setResolvedBy(operator);
        CustomerSearchEvent saved = eventRepo.save(e);
        log.info("检索事件人工重放 eventId={} customerId={} result={} operator={}",
                eventId, e.getCustomerId(), result, operator);
        audit(operator, "REPLAY", saved, "\"result\":\"" + result + "\"}");
        return toRow(saved);
    }

    /** 丢弃：DEAD → DISCARDED 终态，必填原因（PENDING 毒消息也允许丢弃；SENT/DISCARDED 拒绝）。 */
    @Transactional
    public Map<String, Object> discard(Long eventId, String note, String operator) {
        CustomerSearchEvent e = requireEvent(eventId);
        if (ST_DISCARDED.equals(e.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该事件已是「已丢弃」状态");
        }
        if (ST_SENT.equals(e.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该事件已成功投递（SENT），不可丢弃（eventId=" + eventId + "）");
        }
        String n = note == null ? "" : note.trim();
        if (n.isEmpty() || n.length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "丢弃原因必填（至少 2 个字），便于审计追溯");
        }
        String from = e.getStatus();
        e.setStatus(ST_DISCARDED);
        e.setResolveNote(clip(n, 256));
        e.setResolvedAt(OffsetDateTime.now());
        e.setResolvedBy(operator);
        CustomerSearchEvent saved = eventRepo.save(e);
        log.info("检索事件人工丢弃 eventId={} customerId={} operator={} note={}", eventId,
                e.getCustomerId(), operator, n);
        audit(operator, "DISCARD", saved,
                "\"from\":\"" + from + "\",\"note\":\"" + esc(n) + "\"}");
        return toRow(saved);
    }

    /** 全量重建审计（reindexAll 本体在 CustomerSearchService，此处仅补留痕）。 */
    public void auditReindex(int indexed, String operator) {
        audit.record(BIZ_TYPE, "REINDEX", operator, "REINDEX",
                "{\"indexed\":" + indexed + ",\"index\":\"meiyun-customer\"}");
    }

    // ==================== 内部 ====================

    private CustomerSearchEvent requireEvent(Long eventId) {
        if (eventId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventId 必填");
        }
        return eventRepo.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "事件不存在或无权查看"));
    }

    /** 列表行读模型：事件字段 + 回查 PG 富化客户名/手机号/门店（客户已删时 name=null）。 */
    private Map<String, Object> toRow(CustomerSearchEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", e.getEventId());
        m.put("eventType", e.getEventType());
        m.put("customerId", e.getCustomerId());
        m.put("status", e.getStatus());
        m.put("statusLabel", statusLabel(e.getStatus()));
        m.put("retryCount", e.getRetryCount());
        m.put("lastError", e.getLastError());
        m.put("createdAt", e.getCreatedAt());
        m.put("sentAt", e.getSentAt());
        m.put("resolvedAt", e.getResolvedAt());
        m.put("resolvedBy", e.getResolvedBy());
        m.put("resolveNote", e.getResolveNote());
        Customer c = customerRepo.findById(e.getCustomerId()).orElse(null);
        if (c != null) {
            m.put("customerName", c.getName());
            m.put("phone", c.getPhone());
            m.put("storeCode", c.getStoreCode());
        } else {
            m.put("customerName", null);
            m.put("phone", null);
            m.put("storeCode", null);
        }
        return m;
    }

    private void audit(String operator, String action, CustomerSearchEvent e, String tail) {
        String p = "{\"eventId\":" + e.getEventId()
                + ",\"customerId\":\"" + esc(e.getCustomerId()) + "\","
                + "\"operator\":\"" + esc(operator) + "\","
                + tail;
        audit.record(BIZ_TYPE, String.valueOf(e.getEventId()), operator, action, p);
    }

    public static String statusLabel(String s) {
        return switch (s) {
            case ST_PENDING -> "待投递";
            case ST_SENT -> "已投递";
            case ST_DEAD -> "投递失败";
            case ST_DISCARDED -> "已丢弃";
            default -> s;
        };
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
