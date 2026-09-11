package com.meiyun.txn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 术后 SOP 超期未回访巡检 + 升级通知（P5-B30）。
 *
 * <p>范式复刻 {@link ApprovalSlaJob}：{@code @Scheduled} 限批 50 条 FIFO、单条 try-catch、
 * 不加方法级 @Transactional（每条独立 save，单条失败不回滚整批）、SLF4J 日志。每分钟一轮：</p>
 * <ol>
 *   <li>扫 PENDING + SOP 节点（sop_batch_id 非空）+ plan_date 早于今日零点（业务时区 +8）+ 未升级；</li>
 *   <li>置 escalated=true（只升级一次），并给本门店 STORE_MGR 落 FOLLOWUP/URGENT 通知；</li>
 *   <li>通知幂等 idemKey=SOP-ESC:{followupNo}:{staffId}，收件人显式关闭 FOLLOWUP 类别订阅则免打扰。</li>
 * </ol>
 * <p>org 不可用时目标人软降级为空（本轮只置升级标记、不落通知，下轮自愈补通知），不阻断主链路。</p>
 */
@Component
public class FollowupSopDueJob {

    private static final Logger log = LoggerFactory.getLogger(FollowupSopDueJob.class);
    private static final ZoneOffset BIZ_TZ = ZoneOffset.ofHours(8);

    private final FollowupRepository followupRepo;
    private final FollowupSopBatchRepository batchRepo;
    private final NotificationRepository notificationRepo;
    private final NotifyPreferenceRepository preferenceRepo;
    private final OrgStaffClient orgStaffClient;

    public FollowupSopDueJob(FollowupRepository followupRepo, FollowupSopBatchRepository batchRepo,
                             NotificationRepository notificationRepo,
                             NotifyPreferenceRepository preferenceRepo, OrgStaffClient orgStaffClient) {
        this.followupRepo = followupRepo;
        this.batchRepo = batchRepo;
        this.notificationRepo = notificationRepo;
        this.preferenceRepo = preferenceRepo;
        this.orgStaffClient = orgStaffClient;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 25_000L)
    public void scanOverdue() {
        LocalDate today = LocalDate.now(BIZ_TZ);
        List<Followup> overdue = followupRepo.findOverdueForEscalation(today, PageRequest.of(0, 50));
        if (overdue.isEmpty()) return;
        for (Followup f : overdue) {
            try {
                escalate(f);
            } catch (Exception ex) {
                log.warn("术后 SOP 超期升级兜底异常 followupNo={}: {}", f.getFollowupNo(), ex.getMessage());
            }
        }
    }

    /** 置升级标记 + 给本店店长发升级通知（幂等、免打扰）。 */
    private void escalate(Followup f) {
        OffsetDateTime now = OffsetDateTime.now();
        f.setEscalated(true);
        followupRepo.save(f);

        String storeCode = batchRepo.findById(f.getSopBatchId())
                .map(FollowupSopBatch::getStoreCode).orElse(null);
        int notified = notifyStoreManagers(f, storeCode, now);
        log.info("术后 SOP 节点超期升级 followupNo={} batch={} customer={} planDate={} 通知店长{}人",
                f.getFollowupNo(), f.getSopBatchId(), f.getCustomerName(), f.getPlanDate(), notified);
    }

    private int notifyStoreManagers(Followup f, String storeCode, OffsetDateTime now) {
        if (storeCode == null || storeCode.isBlank()) {
            log.warn("术后 SOP 升级缺 storeCode，无法定位本店店长 followupNo={}", f.getFollowupNo());
            return 0;
        }
        List<OrgStaffClient.StaffBrief> managers = orgStaffClient.listStaffByRole("STORE_MGR", storeCode, null);
        if (managers.isEmpty()) {
            log.warn("术后 SOP 升级无目标店长 store={} followupNo={}", storeCode, f.getFollowupNo());
            return 0;
        }
        String title = "术后随访超期升级：" + f.getCustomerName() + "·" + f.getSopLabel();
        String content = String.format("随访单 %s（%s，项目 %s）计划 %s 回访，已超期未完成，请尽快安排跟进。",
                f.getFollowupNo(), f.getSopLabel(), f.getProject(), f.getPlanDate());
        int n = 0;
        for (OrgStaffClient.StaffBrief m : managers) {
            String staffId = m.staffId();
            if (preferenceRepo.findByStaffIdAndCategory(staffId, "FOLLOWUP")
                    .filter(p -> !p.isEnabled()).isPresent()) {
                continue;
            }
            String idemKey = "SOP-ESC:" + f.getFollowupNo() + ":" + staffId;
            if (notificationRepo.existsByIdemKey(idemKey)) continue;
            Notification note = new Notification();
            note.setRecipient(staffId);
            note.setCategory("FOLLOWUP");
            note.setLevel("URGENT");
            note.setTitle(truncate(title, 128));
            note.setContent(truncate(content, 500));
            note.setLink("/sop");
            note.setBizRef(f.getFollowupNo());
            note.setSender("system");
            note.setIdemKey(idemKey);
            note.setCreatedAt(now);
            notificationRepo.save(note);
            n++;
        }
        return n;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
