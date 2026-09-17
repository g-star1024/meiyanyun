package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合规巡检跨服务站内信联动（客户域 {@code ComplianceInspectionJob} → txn 通知中心）。
 *
 * <p>与 B24 {@link InternalOrderController} 相互独立，仅新增内部写入端点，不改任何 B24 文件。
 * 客户域以系统身份（X-Internal-Token）调用 {@code POST /api/txn/internal/compliance-alert}，
 * 本端点按告警级别解析接收人（WARN→STORE_MGR，CRITICAL→REGION_MGR+SUPER_ADMIN），
 * 由 {@link OrgStaffClient} 软降级取人（org 不可用时本轮少发/不发，下轮自愈），
 * 落 {@link Notification} 表（category=SYSTEM，level=WARNING/URGENT），
 * 后端扇出 Job {@link NotificationFanoutJob} 负责 INBOX 送达 + SSE 铃铛推送。
 *
 * <p>权限 {@code internal:notify}：内部 system 身份天然放行，普通登录人无此码 → 403，
 * 通知写入仅限服务间受信调用。
 */
@RestController
@RequestMapping("/api/txn/internal")
public class InternalNotificationController {

    private static final List<String> LEVELS = List.of("WARN", "CRITICAL");
    private static final String CATEGORY = "SYSTEM";

    private final NotificationRepository notificationRepo;
    private final NotifyPreferenceRepository preferenceRepo;
    private final OrgStaffClient orgStaffClient;

    public InternalNotificationController(NotificationRepository notificationRepo,
                                          NotifyPreferenceRepository preferenceRepo,
                                          OrgStaffClient orgStaffClient) {
        this.notificationRepo = notificationRepo;
        this.preferenceRepo = preferenceRepo;
        this.orgStaffClient = orgStaffClient;
    }

    /**
     * 合规巡检告警写入：body {level(必填 WARN/CRITICAL), title(必填), content(必填), link, bizRef}。
     * 返回 {level, recipients, sent, muted}（muted=已关闭 SYSTEM 类别订阅的收件人数）。
     */
    @PostMapping("/compliance-alert")
    @RequirePerm("internal:notify")
    public Map<String, Object> complianceAlert(@RequestBody ComplianceAlertRequest req) {
        String level = req.level() == null ? "" : req.level().trim().toUpperCase();
        if (!LEVELS.contains(level)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "告警级别不合法：" + level);
        }
        String title = req.title() == null ? "" : req.title().trim();
        String content = req.content() == null ? "" : req.content().trim();
        if (title.isBlank() || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "告警标题与内容不能为空");
        }
        String bizRef = req.bizRef() == null ? "" : req.bizRef().trim();

        boolean critical = "CRITICAL".equals(level);
        List<String> roles = critical ? List.of("REGION_MGR", "SUPER_ADMIN") : List.of("STORE_MGR");
        String notifLevel = critical ? "URGENT" : "WARNING";

        Set<String> recipients = new LinkedHashSet<>();
        for (String role : roles) {
            for (OrgStaffClient.StaffBrief s : orgStaffClient.listStaffByRole(role, null, null)) {
                recipients.add(s.staffId());
            }
        }

        int sent = 0;
        int muted = 0;
        OffsetDateTime now = OffsetDateTime.now();
        for (String staffId : recipients) {
            if (preferenceRepo.findByStaffIdAndCategory(staffId, CATEGORY)
                    .filter(p -> !p.isEnabled()).isPresent()) {
                muted++;
                continue;
            }
            String idemKey = "COMPLIANCE:" + bizRef + ":" + staffId + ":" + level;
            if (notificationRepo.existsByIdemKey(idemKey)) {
                continue;
            }
            Notification note = new Notification();
            note.setRecipient(staffId);
            note.setCategory(CATEGORY);
            note.setLevel(notifLevel);
            note.setTitle(truncate(title, 128));
            note.setContent(truncate(content, 500));
            note.setLink(req.link());
            note.setBizRef(bizRef.isBlank() ? null : truncate(bizRef, 32));
            note.setSender("system");
            note.setIdemKey(idemKey);
            note.setCreatedAt(now);
            notificationRepo.save(note);
            sent++;
        }
        return Map.of("level", level, "recipients", recipients.size(), "sent", sent, "muted", muted);
    }

    /** 合规巡检告警写入请求体（与客户域调用方字段对齐）。 */
    public record ComplianceAlertRequest(String level, String title, String content, String link, String bizRef) {
    }

    private static String truncate(String s, int max) {
        if (s == null || s.isBlank()) {
            return s;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}