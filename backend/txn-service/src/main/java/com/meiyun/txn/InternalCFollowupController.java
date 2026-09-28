package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import com.meiyun.txn.audit.AuditRecorder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

/**
 * C 端随访 internal 端点（DESIGN-C §四端点 #8 落库通道，C-B5）：C 端会员在小程序
 * 对本人随访单提交满意度/不良反应自评，与 B 端随访台账/不良反应处置台天然同账（零适配）。
 *
 * <p>仅供 c-service 经 X-Internal-Token 系统身份调用（网关对 /api/*&#8203;/internal/** 外部 404 隐身）。
 * 与 B 端 {@link FollowupService#complete} 差异：①C 端无恢复情况控件，recovery 固定 GOOD 留痕；
 * ②行级归属双保险——随访客户须与入参 customerId 一致（c-service 侧 guard 已行级隔离）；
 * ③非 PENDING 幂等重放直返当前态（C 端双击/重试友好），不抛 400；④followupByName 固定
 * 「客户自评」（区别于员工回访留痕）；⑤审计 actor 固定 "c-service"（溯源通道），payload 带
 * channel:C。不良反应勾选说明必填并自动 adverseStatus=OPEN（与 B 端同口径，转处置台跟进）。
 */
@RestController
@RequestMapping("/api/txn/internal/c-followups")
public class InternalCFollowupController {

    private final FollowupRepository followupRepo;
    private final AuditRecorder audit;

    public InternalCFollowupController(FollowupRepository followupRepo, AuditRecorder audit) {
        this.followupRepo = followupRepo;
        this.audit = audit;
    }

    /** C 端随访自评命令（满意度 1-5 必填；不良反应勾选时说明必填；note 可空）。 */
    public record SubmitCFollowupCmd(String customerId, Integer satisfaction, String note,
                                     Boolean adverseReaction, String adverseNote) {
    }

    /** C 端随访视图（internal 响应；状态映射归 c-service 适配层）。 */
    public record CFollowupView(Long id, String followupNo, String customerId, String status,
                                Integer satisfaction, String recovery, boolean adverseReaction,
                                String adverseStatus, String doneAt) {
    }

    /** C 端提交随访自评：归属校验 → 幂等重放直返 → 全量校验 → DONE 落库 → 审计 FOLLOWUP/COMPLETE。 */
    @PostMapping("/{id}/submit")
    @RequirePerm("internal:c-followup")
    @Transactional
    public CFollowupView submit(@PathVariable Long id, @RequestBody SubmitCFollowupCmd cmd) {
        if (cmd == null) {
            throw badRequest("请求体不能为空");
        }
        String customerId = cmd.customerId() == null ? "" : cmd.customerId().trim();
        if (customerId.isEmpty()) {
            throw badRequest("客户编号不能为空");
        }
        Followup f = followupRepo.findById(id)
                .orElseThrow(() -> badRequest("随访不存在: " + id));
        if (!customerId.equals(f.getCustomerId())) {
            throw badRequest("随访归属与客户不一致");
        }
        if (!FollowupService.ST_PENDING.equals(f.getStatus())) {
            return view(f);
        }
        if (cmd.satisfaction() == null || cmd.satisfaction() < 1 || cmd.satisfaction() > 5) {
            throw badRequest("满意度为必填项，取值 1-5 星");
        }
        boolean adverse = Boolean.TRUE.equals(cmd.adverseReaction());
        String adverseNote = cmd.adverseNote() == null ? "" : cmd.adverseNote().trim();
        if (adverse && adverseNote.isEmpty()) {
            throw badRequest("已勾选不良反应，请填写不良反应说明（便于转投诉/医疗风险跟进）");
        }
        OffsetDateTime now = OffsetDateTime.now();
        f.setStatus(FollowupService.ST_DONE);
        f.setSatisfaction(cmd.satisfaction());
        f.setRecovery("GOOD");
        f.setAdverseReaction(adverse);
        f.setAdverseNote(adverse ? truncate(adverseNote, 500) : null);
        if (adverse) {
            f.setAdverseStatus("OPEN");
        }
        f.setNote(truncate(trimToNull(cmd.note()), 65535));
        f.setFollowupByName("客户自评");
        f.setDoneAt(now);
        Followup saved = followupRepo.save(f);
        audit.record("FOLLOWUP", saved.getFollowupNo(), "c-service", "COMPLETE",
                "{\"customerId\":\"" + customerId + "\",\"satisfaction\":" + cmd.satisfaction()
                        + ",\"recovery\":\"GOOD\",\"adverseReaction\":" + adverse
                        + ",\"channel\":\"C\"}");
        return view(saved);
    }

    private static CFollowupView view(Followup f) {
        return new CFollowupView(f.getId(), f.getFollowupNo(), f.getCustomerId(), f.getStatus(),
                f.getSatisfaction(), f.getRecovery(), f.isAdverseReaction(),
                f.getAdverseStatus(), f.getDoneAt() == null ? null : String.valueOf(f.getDoneAt()));
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
