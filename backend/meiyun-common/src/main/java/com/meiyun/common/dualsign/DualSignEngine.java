package com.meiyun.common.dualsign;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 双签引擎（九条红线之首，数据字典 §1.6）。
 *
 * <p>核心能力：
 * <ol>
 *   <li>按「业务类型 × 金额」确定档位与所需签数（{@link #requiredSignCount}）；</li>
 *   <li>退款/退卡属于出账类，L1 区间被 BR-BL-013 抬升为 L2（至少双签）；</li>
 *   <li>5 类业务不看金额一律至少双签（{@link BizType#isForceDual}）；</li>
 *   <li>系统级硬校验：①两签不同人 ②不同岗位序列（资产转移例外）③医疗类第二签须持证 ④L3 需第三签。</li>
 * </ol>
 *
 * <p>本类为纯 Java，零业务依赖，可直接被各微服务与单测复用。
 */
public final class DualSignEngine {

    private DualSignEngine() {
    }

    /** 不看金额一律至少双签的业务。 */
    private static final Set<BizType> FORCE_DUAL = EnumSet.of(
            BizType.REFUND, BizType.CARD_CANCEL, BizType.CONSUMABLE, BizType.SCRAP, BizType.CASH_HANDOVER);

    /** 第二签须持医疗执业资质的业务。 */
    private static final Set<BizType> MEDICAL = EnumSet.of(
            BizType.TREATMENT, BizType.CONSUMABLE, BizType.SCRAP);

    /**
     * 计算最终档位（含 BR-BL-013 出账类抬升）。
     */
    public static SignTier computeTier(BizType biz, long amountCents) {
        SignTier base = SignTier.of(amountCents);
        if ((biz == BizType.REFUND || biz == BizType.CARD_CANCEL) && base == SignTier.L1) {
            return SignTier.L2; // BR-BL-013：出账类 L1 抬升 L2
        }
        return base;
    }

    /**
     * 所需签署人数：L3=3，L2 或强制双签=2，其余=1。
     */
    public static int requiredSignCount(BizType biz, long amountCents) {
        SignTier tier = computeTier(biz, amountCents);
        if (tier == SignTier.L3) {
            return 3;
        }
        if (tier == SignTier.L2 || FORCE_DUAL.contains(biz)) {
            return 2;
        }
        return 1;
    }

    /**
     * 校验一次签署请求，违规时抛 {@link DualSignException}。
     */
    public static void validate(SignRequest req) {
        List<String> errors = validateReturningErrors(req);
        if (!errors.isEmpty()) {
            throw new DualSignException(errors);
        }
    }

    /**
     * 校验并返回全部违规项（不抛异常），便于前端逐条展示。
     */
    public static List<String> validateReturningErrors(SignRequest req) {
        List<String> errors = new ArrayList<>();
        BizType biz = req.bizType();
        int need = requiredSignCount(biz, req.amountCents());

        if (req.signer1() == null) {
            errors.add("缺发起签（第一签）");
            return errors;
        }

        boolean dual = need >= 2;
        if (dual) {
            if (req.signer2() == null) {
                errors.add("该业务/金额需双签，缺少第二签");
            } else {
                Signer s1 = req.signer1();
                Signer s2 = req.signer2();
                if (s1.personId().equals(s2.personId())) {
                    errors.add("① 两签不得为同一人");
                }
                if (biz == BizType.ASSET_TRANSFER) {
                    // 唯一合法例外：同岗位序列但分属不同门店主体 → 不冲突
                    if (s1.role().equals(s2.role()) && s1.storeId().equals(s2.storeId())) {
                        errors.add("② 资产转移：同岗位且同门店冲突（须为不同门店主体）");
                    }
                } else {
                    if (s1.roleSequence().equals(s2.roleSequence())) {
                        errors.add("② 两签不得为同一岗位序列");
                    }
                }
                if (MEDICAL.contains(biz) && !s2.medicalLicensed()) {
                    errors.add("③ 医疗类业务第二签须持医疗执业资质");
                }
            }
        }

        if (need == 3 && req.signer3() == null) {
            errors.add("L3 档需集团复核第三签");
        }
        return errors;
    }
}
