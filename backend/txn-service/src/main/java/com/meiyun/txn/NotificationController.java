package com.meiyun.txn;

import com.meiyun.security.DataScope;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息通知中心 REST（B20）：登录人按工号拉取自己的站内通知。
 * 网关 /api/txn 前缀已路由至 txn-service，无需改网关。
 *
 * <p>不挂类级权限码——通知是全员基础能力（任何登录员工都可能收到审批催办/后续业务通知），
 * 仅要求登录（AuthInterceptor 无 token → 401）；数据隔离在服务层按 recipient = 当前工号强制过滤。
 * 当前唯一生产源为 {@link ApprovalSlaJob} 审批 SLA 超时催办（category=APPROVAL, level=URGENT）。
 */
@RestController
@RequestMapping("/api/txn/notifications")
public class NotificationController {

    private final NotificationRepository notificationRepo;

    public NotificationController(NotificationRepository notificationRepo) {
        this.notificationRepo = notificationRepo;
    }

    /** 我的通知列表（最新在前），并附未读数：{items:[...], unread: n}。 */
    @GetMapping
    public Map<String, Object> mine() {
        String me = currentStaffId();
        List<Notification> items = notificationRepo.findByRecipientOrderByIdDesc(me);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("unread", notificationRepo.countByRecipientAndReadFalse(me));
        return out;
    }

    /** 未读数（铃铛角标轮询用）。 */
    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount() {
        String me = currentStaffId();
        return Map.of("unread", notificationRepo.countByRecipientAndReadFalse(me));
    }

    /** 单条标记已读（仅本人通知可操作，越权 404 不泄露存在性）。 */
    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable Long id) {
        String me = currentStaffId();
        Notification n = notificationRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "通知不存在"));
        if (!me.equals(n.getRecipient())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知不存在");
        }
        if (!n.isRead()) {
            n.setRead(true);
            notificationRepo.save(n);
        }
        return Map.of("id", id, "read", true);
    }

    /** 全部已读（当前登录人）。 */
    @PostMapping("/read-all")
    public Map<String, Object> markAllRead() {
        String me = currentStaffId();
        int updated = notificationRepo.markAllRead(me);
        return Map.of("updated", updated);
    }

    private static String currentStaffId() {
        var u = DataScope.current();
        if (u == null || u.staffId() == null || u.staffId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已失效");
        }
        return u.staffId();
    }
}
