package com.meiyun.common.dualsign;

/**
 * 一次双签请求（发起业务 + 金额 + 最多三签署人）。
 *
 * @param bizType     业务类型
 * @param amountCents 金额（分）
 * @param signer1     第一签（必填）
 * @param signer2     第二签（双签/L3 必填）
 * @param signer3     第三签（L3 集团复核必填）
 */
public record SignRequest(
        BizType bizType,
        long amountCents,
        Signer signer1,
        Signer signer2,
        Signer signer3
) {
    public SignRequest {
        if (bizType == null) {
            throw new IllegalArgumentException("bizType 不可为空");
        }
        if (amountCents < 0) {
            throw new IllegalArgumentException("金额不可为负");
        }
    }
}
