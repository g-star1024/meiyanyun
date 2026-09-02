package com.meiyun.common.dualsign;

/**
 * 业务类型（数据字典 §1.6 / §4.3）。
 *
 * @param forceDual 是否「不看金额一律至少双签」（退款/退卡/耗材领用/报损/现金交接）
 * @param medical   第二签是否须持医疗执业资质（治疗执行/划扣、耗材领用(管制类)、报损(药品/注射类)）
 */
public enum BizType {
    CONSUMABLE("耗材领用", true, true),
    TREATMENT("治疗执行/划扣", false, true),
    REFUND("退款", true, false),
    CARD_CANCEL("退卡", true, false),
    PRICE_ADJUST("调价/折扣", false, false),
    COMMISSION("提成/佣金", false, false),
    INVOICE_RED("发票红冲", false, false),
    SCRAP("报损", true, true),
    CASH_HANDOVER("现金交接", true, false),
    COMPLAINT("客诉", false, false),
    /** 资产转移是「同岗位序列冲突」的唯一合法例外（同岗位但分属不同门店主体不冲突）。 */
    ASSET_TRANSFER("资产转移", false, false);

    private final String label;
    private final boolean forceDual;
    private final boolean medical;

    BizType(String label, boolean forceDual, boolean medical) {
        this.label = label;
        this.forceDual = forceDual;
        this.medical = medical;
    }

    public String label() {
        return label;
    }

    public boolean isForceDual() {
        return forceDual;
    }

    public boolean isMedical() {
        return medical;
    }
}
