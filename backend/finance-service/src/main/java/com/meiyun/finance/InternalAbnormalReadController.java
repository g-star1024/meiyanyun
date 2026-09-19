package com.meiyun.finance;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * finance 异常账务账单<b>内部只读</b>端点（B63 卡2 L83，服务间 X-Internal-Token 系统身份）。
 *
 * <p>与写端点 {@link InternalFundWriteController}（{@code internal:fund-write}）隔离为独立类：
 * 本类仅持 {@code internal:abnormal-read} 只读码，专供 txn 统一异常中心聚合拉取，
 * 普通 JWT 登录人无此权限码 → 403；网关层 {@code /internal/**} 对外裸 404。
 *
 * <p>本端点不做 DataScope 收敛——系统身份下 {@link FinAbnormalBillService#list} 本就开放全量；
 * 门店可见性由 txn 聚合层逐行 {@code DataScope.canReadStore} 二次判定，避免越权。
 * 返回只读视图，不暴露 idemKey / outboxId / disposeFundEntryId 等内部列。
 */
@RestController
@RequestMapping("/api/finance/internal/abnormal")
@RequirePerm("internal:abnormal-read")
public class InternalAbnormalReadController {

    private final FinAbnormalBillService abnormalBillService;

    public InternalAbnormalReadController(FinAbnormalBillService abnormalBillService) {
        this.abnormalBillService = abnormalBillService;
    }

    /**
     * 异常账单批量只读归集：GET /api/finance/internal/abnormal/bills。
     * status / type / storeCode 均可选；按登记时间倒序。
     */
    @GetMapping("/bills")
    public List<InternalAbnormalBillView> listBills(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String type,
                                                    @RequestParam(required = false) String storeCode) {
        return abnormalBillService.list(storeCode, status, type).stream()
                .map(InternalAbnormalBillView::from)
                .toList();
    }

    /** 异常账单只读视图（服务间归集契约，剔除幂等键/outbox/动账分录等内部列）。 */
    public record InternalAbnormalBillView(String billNo, String storeCode, String type, String direction,
                                           Long amountFen, String source, String reason, String status,
                                           String approvalNo, String createdBy, String reviewer,
                                           java.time.OffsetDateTime createdAt,
                                           java.time.OffsetDateTime approvedAt,
                                           java.time.OffsetDateTime disposedAt) {

        static InternalAbnormalBillView from(FinAbnormalBill b) {
            return new InternalAbnormalBillView(
                    b.getBillNo(), b.getStoreCode(), b.getType(), b.getDirection(),
                    b.getAmountFen(), b.getSource(), b.getReason(), b.getStatus(),
                    b.getApprovalNo(), b.getCreatedBy(), b.getReviewer(),
                    b.getCreatedAt(), b.getApprovedAt(), b.getDisposedAt());
        }
    }
}
