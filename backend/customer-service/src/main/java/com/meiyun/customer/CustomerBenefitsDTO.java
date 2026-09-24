package com.meiyun.customer;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 客户 360 权益读模型（B93，GET /customer/{id}/benefits）：当期钱包（懒发放触发）+ 近 20 条核销流水。
 * monthQuota 为等级当期月配额（读 freeCareTimesOf 兜底同口径），wallet 不存在（等级无权益）时 wallets 为空。
 */
public record CustomerBenefitsDTO(List<BenefitWalletDTO> wallets, List<BenefitWriteoffDTO> writeoffs) {

    public record BenefitWalletDTO(String benefitType, String period, String levelSnap,
                                   int totalTimes, int usedTimes, int remaining, int monthQuota) {}

    public record BenefitWriteoffDTO(String writeoffNo, String period, String projectName, String status,
                                     String reason, String operator, String storeName, OffsetDateTime createdAt) {}
}
