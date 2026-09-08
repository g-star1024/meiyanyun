package com.meiyun.txn;

import com.meiyun.security.DataScope;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 消息通知中心 REST（B20）：登录人按工号拉取自己的站内通知。
 * 网关 /api/txn 前缀已路由至 txn-service，无需改网关。
 *
 * <p>不挂类级权限码——通知是全员基础能力（任何登录员工都可能收到审批催办/后续业务通知），
 * 仅要求登录（AuthInterceptor 无 token → 401）；数据隔离在服务层按 recipient = 当前工号强制过滤。
 * 当前唯一生产源为 {@link ApprovalSlaJob} 审批 SLA 超时催办（category=APPROVAL, level=URGENT）。
 *
 * <p>B21 增补 GET/PUT /preferences：通知偏好后端持久化（订阅开关 + 渠道位），
 * 替代前端本地态；SLA 催办落库前按 APPROVAL 偏好过滤（关闭则免打扰）。
 */
@RestController
@RequestMapping("/api/txn/notifications")
public class NotificationController {

    /** 与 notification.category / 前端 NotifyCategory 对齐的五大类别。 */
    private static final List<String> CATEGORIES = List.of("APPROVAL", "CUSTOMER", "INVENTORY", "MARKETING", "SYSTEM");
    /** 合法渠道码（INBOX 为当前唯一有发送链路的渠道，其余为 B22+ 渠道网关预留位）。 */
    private static final Set<String> CHANNELS = Set.of("INBOX", "SMS", "WECHAT", "EMAIL");

    private final NotificationRepository notificationRepo;
    private final NotifyPreferenceRepository preferenceRepo;

    public NotificationController(NotificationRepository notificationRepo,
                                  NotifyPreferenceRepository preferenceRepo) {
        this.notificationRepo = notificationRepo;
        this.preferenceRepo = preferenceRepo;
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

    /**
     * 我的通知偏好（B21）：五大类别全量返回；无落库行的类别回落系统默认
     * （订阅开启 enabled=true、渠道仅 INBOX；SYSTEM 默认 INBOX+SMS 与前端原默认态对齐）。
     */
    @GetMapping("/preferences")
    public Map<String, Object> preferences() {
        String me = currentStaffId();
        Map<String, NotifyPreference> saved = new LinkedHashMap<>();
        for (NotifyPreference p : preferenceRepo.findByStaffId(me)) {
            saved.put(p.getCategory(), p);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (String c : CATEGORIES) {
            NotifyPreference p = saved.get(c);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", c);
            if (p == null) {
                row.put("enabled", true);
                row.put("channels", "SYSTEM".equals(c) ? List.of("INBOX", "SMS") : List.of("INBOX"));
            } else {
                row.put("enabled", p.isEnabled());
                row.put("channels", splitChannels(p.getChannels()));
            }
            items.add(row);
        }
        return Map.of("items", items);
    }

    /**
     * 更新单个类别偏好（B21，upsert）：body {category, enabled, channels:[...]}。
     * 校验类别合法、渠道码合法；订阅开启时渠道必须含 INBOX（当前唯一发送链路，关闭 INBOX 等于收不到）。
     * 返回更新后的全量偏好（与 GET 同构，前端一次替换本地态）。
     */
    @PutMapping("/preferences")
    public Map<String, Object> updatePreference(@RequestBody Map<String, Object> body) {
        String me = currentStaffId();
        String category = body.get("category") == null ? "" : body.get("category").toString().trim().toUpperCase();
        if (!CATEGORIES.contains(category)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知类别不合法：" + category);
        }
        boolean enabled = !Boolean.FALSE.equals(body.get("enabled"));
        List<String> channels = new ArrayList<>();
        Object raw = body.get("channels");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                String ch = o == null ? "" : o.toString().trim().toUpperCase();
                if (ch.isBlank()) continue;
                if (!CHANNELS.contains(ch)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知渠道不合法：" + ch);
                }
                if (!channels.contains(ch)) channels.add(ch);
            }
        }
        if (enabled && !channels.contains("INBOX")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "订阅开启时必须保留站内信（INBOX）渠道，其余渠道网关尚未开通");
        }
        if (!enabled) {
            channels = List.of();
        }
        NotifyPreference p = preferenceRepo.findByStaffIdAndCategory(me, category)
                .orElseGet(NotifyPreference::new);
        p.setStaffId(me);
        p.setCategory(category);
        p.setEnabled(enabled);
        p.setChannels(String.join(",", channels));
        preferenceRepo.save(p);
        return preferences();
    }

    private static List<String> splitChannels(String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String s : raw.split(",")) {
                if (!s.isBlank() && !out.contains(s.trim())) out.add(s.trim());
            }
        }
        return out;
    }

    private static String currentStaffId() {
        var u = DataScope.current();
        if (u == null || u.staffId() == null || u.staffId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已失效");
        }
        return u.staffId();
    }
}
