package com.meiyun.txn;

import com.meiyun.security.DataScope;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.format.DateTimeFormatter;
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
 * B26/B57 四渠道发送链路（INBOX/SMS/WECHAT/EMAIL）与配置窗口已全量落地。
 * B60 卡1 偏好契约再扩个人级免打扰时段（quietEnabled/quietStart/quietEnd，L65）。
 */
@RestController
@RequestMapping("/api/txn/notifications")
public class NotificationController {

    /** 与 notification.category / 前端 NotifyCategory 对齐的五大类别。 */
    private static final List<String> CATEGORIES = List.of("APPROVAL", "CUSTOMER", "INVENTORY", "MARKETING", "SYSTEM");
    /** 合法渠道码（四渠道 B26/B57 均已落地：INBOX 站内信恒送达，其余经配置窗口网关投递，未配网关落 SKIPPED）。 */
    private static final Set<String> CHANNELS = Set.of("INBOX", "SMS", "WECHAT", "EMAIL");
    /** 个人免打扰默认时段（无落库行回落，与前端设置页骨架一致）。 */
    private static final String DEFAULT_QUIET_START = "22:00";
    private static final String DEFAULT_QUIET_END = "08:00";
    private static final DateTimeFormatter QUIET_HHMM = DateTimeFormatter.ofPattern("HH:mm");

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
     * 我的通知偏好（B21，B60 扩个人免打扰）：五大类别全量返回；无落库行的类别回落系统默认
     * （订阅开启 enabled=true、渠道仅 INBOX；SYSTEM 默认 INBOX+SMS 与前端原默认态对齐；
     * 个人免打扰 quietEnabled=false，时段回落 22:00–08:00 仅作前端展示默认值）。
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
                row.put("quietEnabled", false);
                row.put("quietStart", DEFAULT_QUIET_START);
                row.put("quietEnd", DEFAULT_QUIET_END);
            } else {
                row.put("enabled", p.isEnabled());
                row.put("channels", splitChannels(p.getChannels()));
                row.put("quietEnabled", p.isQuietEnabled());
                row.put("quietStart", p.getQuietStart() == null ? DEFAULT_QUIET_START : p.getQuietStart());
                row.put("quietEnd", p.getQuietEnd() == null ? DEFAULT_QUIET_END : p.getQuietEnd());
            }
            items.add(row);
        }
        return Map.of("items", items);
    }

    /**
     * 更新单个类别偏好（B21，upsert；B60 扩个人免打扰时段）：
     * body {category, enabled, channels:[...], quietEnabled, quietStart:"HH:mm", quietEnd:"HH:mm"}。
     * 校验类别合法、渠道码合法、时段为 HH:mm 且起止不相等（跨午夜允许 start&gt;end，如 22:00–08:00）；
     * 订阅开启时渠道必须含 INBOX（站内信是兜底链路，关闭 INBOX 等于收不到）。
     * quietEnabled=true 但缺时段时回落 22:00–08:00；quietEnabled=false 仍保留时段值供下次开启回显。
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
                    "订阅开启时必须保留站内信（INBOX）渠道");
        }
        if (!enabled) {
            channels = List.of();
        }
        boolean quietEnabled = Boolean.TRUE.equals(body.get("quietEnabled"));
        String quietStart = normalizeHhmm(body.get("quietStart"), DEFAULT_QUIET_START, "免打扰开始时刻");
        String quietEnd = normalizeHhmm(body.get("quietEnd"), DEFAULT_QUIET_END, "免打扰结束时刻");
        if (quietStart.equals(quietEnd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "免打扰开始与结束时刻不能相同（至少相差 1 分钟，跨午夜请用如 22:00–08:00）");
        }
        NotifyPreference p = preferenceRepo.findByStaffIdAndCategory(me, category)
                .orElseGet(NotifyPreference::new);
        p.setStaffId(me);
        p.setCategory(category);
        p.setEnabled(enabled);
        p.setChannels(String.join(",", channels));
        p.setQuietEnabled(quietEnabled);
        p.setQuietStart(quietStart);
        p.setQuietEnd(quietEnd);
        preferenceRepo.save(p);
        return preferences();
    }

    /** 解析 HH:mm 时段：null/空白回落默认值，格式非法（含非 24 小时制）→ 400。 */
    private static String normalizeHhmm(Object raw, String fallback, String fieldLabel) {
        if (raw == null) {
            return fallback;
        }
        String v = raw.toString().trim();
        if (v.isEmpty()) {
            return fallback;
        }
        try {
            return QUIET_HHMM.format(java.time.LocalTime.parse(v, QUIET_HHMM));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    fieldLabel + "格式非法，应为 HH:mm（00:00–23:59）：" + v);
        }
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
