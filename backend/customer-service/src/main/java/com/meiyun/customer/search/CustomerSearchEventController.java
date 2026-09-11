package com.meiyun.customer.search;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 客户检索事件处置台（B32）。
 *
 * <p>收口 B30 outbox 的 DEAD 事件：分页查询/状态 KPI/人工重试/立即重放/丢弃。
 * 全部端点要求 {@code customer:search:admin}（仅区域经理/超管）；事件无门店列，为区域/集团级全局运维视图，
 * 操作人一律取 {@link DataScope#currentActor()}，请求体不接受 actor 字段。
 */
@RestController
@RequestMapping("/api/customer/search-events")
public class CustomerSearchEventController {

    private final CustomerSearchEventAdminService admin;

    public CustomerSearchEventController(CustomerSearchEventAdminService admin) {
        this.admin = admin;
    }

    /** 事件分页清单：status（PENDING/SENT/DEAD/DISCARDED）、customerId、eventId 过滤。 */
    @GetMapping
    @RequirePerm("customer:search:admin")
    public Page<Map<String, Object>> list(
            @PageableDefault(size = 20) Pageable pageable,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) Long eventId) {
        return admin.list(pageable, status, customerId, eventId);
    }

    /** 状态 KPI：pending/sent/dead/discarded 四态计数。 */
    @GetMapping("/stats")
    @RequirePerm("customer:search:admin")
    public Map<String, Long> stats() {
        return admin.stats();
    }

    /** 人工重试：DEAD → PENDING 交回 10 秒中继（retryCount 归零）。 */
    @PostMapping("/{eventId}/retry")
    @RequirePerm("customer:search:admin")
    public Map<String, Object> retry(@PathVariable Long eventId) {
        return admin.retry(eventId, DataScope.currentActor());
    }

    /** 立即重放：当场回查 PG 同步 upsert，返回 SENT/RETRY/DEAD 结果，无需等待中继。 */
    @PostMapping("/{eventId}/replay")
    @RequirePerm("customer:search:admin")
    public Map<String, Object> replay(@PathVariable Long eventId) {
        return admin.replay(eventId, DataScope.currentActor());
    }

    /** 丢弃：DEAD/PENDING → DISCARDED 终态，原因必填（随审计留痕）。 */
    @PostMapping("/{eventId}/discard")
    @RequirePerm("customer:search:admin")
    public Map<String, Object> discard(@PathVariable Long eventId, @RequestBody DiscardReq req) {
        String note = req == null ? null : req.note();
        return admin.discard(eventId, note, DataScope.currentActor());
    }

    /** 丢弃请求体：note 人工原因（≥2 字，服务端校验）。 */
    public record DiscardReq(String note) {}
}
