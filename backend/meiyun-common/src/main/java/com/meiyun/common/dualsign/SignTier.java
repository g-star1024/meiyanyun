package com.meiyun.common.dualsign;

/**
 * 签署金额分档（B19 与审批中心 TxnService.tierFor 阈值对齐，双轨统一）。
 * 金额单位为「分」：
 *   L1 单签      < 500,000 分（¥5,000）
 *   L2 双签      500,000 ~ 1,999,999 分（¥5,000 ~ ¥19,999）
 *   L3 双签+复核 ≥ 2,000,000 分（¥20,000）
 */
public enum SignTier {
    /** 单签区间（退款/退卡会被 BR-BL-013 抬升到 L2）。 */
    L1(500_000L),
    /** 双签。 */
    L2(2_000_000L),
    /** 双签 + 复核（三签；审批中心为区域经理复审阶段）。 */
    L3(Long.MAX_VALUE);

    /** 该档上限（分），含。L3 取最大。 */
    private final long upperBoundCents;

    SignTier(long upperBoundCents) {
        this.upperBoundCents = upperBoundCents;
    }

    public long upperBoundCents() {
        return upperBoundCents;
    }

    /** 按金额计算基础档位（不含业务类型修正）。 */
    public static SignTier of(long amountCents) {
        if (amountCents < 0) {
            throw new IllegalArgumentException("金额不可为负");
        }
        if (amountCents < 500_000L) {
            return L1;
        }
        if (amountCents < 2_000_000L) {
            return L2;
        }
        return L3;
    }
}
