package com.meiyun.common.dualsign;

/**
 * 签署金额分档（数据字典 §1.6）。
 * 金额单位为「分」。阈值与数据字典一致：
 *   L1 单签      < 1,000,000 分（¥10,000）
 *   L2 双签      1,000,000 ~ 9,999,999 分（¥10,000 ~ ¥99,999）
 *   L3 双签+复核 ≥ 10,000,000 分（¥100,000）
 */
public enum SignTier {
    /** 单签区间（退款/退卡会被 BR-BL-013 抬升到 L2）。 */
    L1(1_000_000L),
    /** 双签。 */
    L2(10_000_000L),
    /** 双签 + 集团复核（三签）。 */
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
        if (amountCents < 1_000_000L) {
            return L1;
        }
        if (amountCents < 10_000_000L) {
            return L2;
        }
        return L3;
    }
}
