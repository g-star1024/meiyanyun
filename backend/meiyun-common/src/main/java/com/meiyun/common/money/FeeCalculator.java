package com.meiyun.common.money;

/**
 * 退卡/退款手续费计算（BR-BL-012 / C-05 裁定 / 数据字典 §1.7）。
 *
 * <p>规则：
 * <ul>
 *   <li>默认 退卡金额 = 余额×0.9、手续费 = 余额×0.1（费率 1000 bps）；</li>
 *   <li>医疗禁忌确诊退卡 → 默认免收（手续费 = 0）；</li>
 *   <li>操作人可逐单覆盖（扣否/扣多少）→ 手动窗口；</li>
 *   <li>系统仅强制「退卡金额 + 手续费 = 卡内余额」，不强制比例为 10%。</li>
 * </ul>
 */
public final class FeeCalculator {

    private FeeCalculator() {
    }

    /** 默认手续费率：10%（基点 1000 / 10000）。 */
    public static final long DEFAULT_FEE_BPS = 1000;

    /**
     * @param balanceCents        卡内余额（分）
     * @param isMedical           是否医疗禁忌确诊退卡（免收）
     * @param manualOverride      操作人是否手动覆盖手续费
     * @param feeCentsOverride    手动指定的手续费（分），manualOverride 为 true 时必填
     * @return 含 手续费/退卡金额/是否免收/是否手动覆盖 的方案
     */
    public static FeePlan compute(long balanceCents, boolean isMedical, boolean manualOverride, Long feeCentsOverride) {
        if (balanceCents < 0) {
            throw new IllegalArgumentException("卡内余额不可为负");
        }
        long feeCents;
        boolean medicalWaived = false;
        boolean manualOverridden = false;

        if (isMedical) {
            feeCents = 0;
            medicalWaived = true;
        } else if (manualOverride) {
            if (feeCentsOverride == null || feeCentsOverride < 0) {
                throw new IllegalArgumentException("手动覆盖手续费时必须给出非负的手续费金额");
            }
            feeCents = feeCentsOverride;
            manualOverridden = true;
        } else {
            // 与 DDL chk_tk_fee_override 的「fee = balance / 10」整数除法严格一致（整除截断，至多差 9 分）
            feeCents = balanceCents / 10;
        }

        long refundCents = balanceCents - feeCents;

        // ID-8 / chk_tk_balance：退卡金额 + 手续费 = 卡内余额（禁忌免收时 fee=0 → refund=balance）
        if (refundCents + feeCents != balanceCents) {
            throw new IllegalStateException("退卡金额 + 手续费 必须等于卡内余额");
        }
        return new FeePlan(balanceCents, feeCents, refundCents, medicalWaived, manualOverridden);
    }

    /** 退款手续费方案（默认 0，支持手动覆盖）。 */
    public record FeePlan(
            long balanceCents,
            long feeCents,
            long refundCents,
            boolean medicalWaived,
            boolean manualOverridden
    ) {
    }
}
