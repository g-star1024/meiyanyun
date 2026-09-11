package com.meiyun.txn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 术后 SOP 超期升级执行器（P5-B31 抽自 {@link FollowupSopDueJob}）：
 * 置单节点 escalated=true + 给本门店 STORE_MGR 落 FOLLOWUP/URGENT 通知。
 *
 * <p>供两处复用：60s 定时巡检兜底（{@link FollowupSopDueJob}）、SOP 看板「一键升级」
 * （{@link FollowupSopTemplateService}）。通知幂等 idemKey=SOP-ESC:{followupNo}:{staffId}，
 * 收件人显式关闭 FOLLOWUP 类别订阅则免打扰。org 不可用时目标人软降级为空
 * （本轮只置升级标记、不落通知，下轮自愈补通知），不阻断主链路。</p>
 */
@Component
public class FollowupSopEscalator {

    private static final Logger log = LoggerFactory.getLogger(FollowupSopEscalator.class);

    private final FollowupSopBatchRepository batchRepo;
    private final NotificationRepository notificationRepo;
    private final NotifyPreferenceRepository preferenceRepo;
    private final OrgStaffClient orgStaffClient;

    public FollowupSopEscalator(FollowupSopBatchRepository batchRepo,
                                NotificationRepository notificationRepo,
                                NotifyPreferenceRepository preferenceRepo,
                                OrgStaffClient orgStaffClient) {
        this.batchRepo = batchRepo;
        this.notificationRepo = notificationRepo;
        this.preferenceRepo = preferenceRepo;
        this.orgStaffClient = orgStaffClient;
    }

    /** 置升级标记 + 给本店店长发升级通知（幂等、免打扰）；返回实际下发通知人数。 */
    public int escalate(Followup f) {
        OffsetDateTime now = OffsetDateTime.now();
        f.setEscalated(true);

        String storeCode = batchRepo.findById(f.getSopBatchId())
                .map(FollowupSopBatch::getStoreCode).orElse(null);
        int notified = notifyStoreManagers(f, storeCode, now);
        log.info("术后 SOP 节点超期升级 followupNo={} batch={} customer={} planDate={} 通知店长{}人",
                f.getFollowupNo(), f.getSopBatchId(), f.getCustomerName(), f.getPlanDate(), notified);
        return notified;
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
