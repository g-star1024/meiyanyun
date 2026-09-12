package com.meiyun.ai.log;

import com.meiyun.ai.domain.AiApprovalRepository;
import com.meiyun.ai.domain.AiModelRepository;
import com.meiyun.security.RequirePerm;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

@RestController
@RequestMapping("/api/ai/logs")
@RequirePerm("aiGateway:view")
public class InvokeLogController {

    private final InvokeLogService logService;
    private final AiApprovalRepository approvalRepo;
    private final AiModelRepository modelRepo;

    public InvokeLogController(InvokeLogService logService,
                               AiApprovalRepository approvalRepo,
                               AiModelRepository modelRepo) {
        this.logService = logService;
        this.approvalRepo = approvalRepo;
        this.modelRepo = modelRepo;
    }

    @GetMapping
    public Page<InvokeLogService.LogView> list(
            @RequestParam(required = false) String featureCode,
            @RequestParam(required = false) Boolean success,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return logService.search(featureCode, success, page, size);
    }

    @GetMapping("/bill")
    public List<InvokeLogService.FeatureBill> bill() {
        return logService.monthlyBill();
    }

    /** 聚合 KPI：A1Gateway/A1Admin/A1Govern 三页共用，前端按字段取用。 */
    @GetMapping("/kpi")
    public InvokeLogService.KpiView kpi() {
        OffsetDateTime monthStart = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .with(TemporalAdjusters.firstDayOfMonth())
                .toLocalDate().atStartOfDay().atOffset(ZoneOffset.ofHours(8));
        long pending = approvalRepo.countByStatus("PENDING");
        long monthApproved = approvalRepo.countByStatusAndDecidedAtGreaterThanEqual("APPROVED", monthStart);
        return logService.kpi(pending, monthApproved, modelRepo.count());
    }
}
