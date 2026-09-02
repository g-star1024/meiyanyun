package com.meiyun.common.dualsign;

import java.util.EnumMap;
import java.util.Map;

/**
 * 签署角色对矩阵（数据字典 §1.6「签署角色对」）。
 * 按「业务类型 × 金额档」双维度确定，非全站统一一对。
 */
public final class SignRoleMatrix {

    private SignRoleMatrix() {
    }

    /** 角色对：第一签角色 / 第二签角色 / L3 追加的集团复核角色（无则 null）。 */
    public record RolePair(String role1, String role2, String l3Role) {
    }

    private static final Map<BizType, RolePair> MATRIX = new EnumMap<>(BizType.class);

    static {
        MATRIX.put(BizType.CONSUMABLE, new RolePair("领用人(咨询师)", "复核人(主理医生)", "集团财务总监"));
        MATRIX.put(BizType.TREATMENT, new RolePair("操作人(治疗师)", "复核人(主理医生)", "集团医疗总监"));
        MATRIX.put(BizType.REFUND, new RolePair("初审人(店长)", "复核人(财务)", "集团财务总监"));
        MATRIX.put(BizType.CARD_CANCEL, new RolePair("初审人(店长)", "复核人(财务)", "集团财务总监"));
        MATRIX.put(BizType.PRICE_ADJUST, new RolePair("申请人(咨询师)", "复核人(店长)", "集团运营总监"));
        MATRIX.put(BizType.COMMISSION, new RolePair("制单人(财务)", "复核人(店长)", "集团财务总监"));
        MATRIX.put(BizType.INVOICE_RED, new RolePair("申请人(财务)", "复核人(店长)", "集团财务总监"));
        MATRIX.put(BizType.SCRAP, new RolePair("报损人(治疗师)", "复核人(主理医生)", "集团财务总监"));
        MATRIX.put(BizType.CASH_HANDOVER, new RolePair("交班人(前台)", "接班人(店长)", null));
        MATRIX.put(BizType.COMPLAINT, new RolePair("受理人(客服)", "裁定人(店长)", "集团客服总监"));
        MATRIX.put(BizType.ASSET_TRANSFER, new RolePair("调出方(店长)", "调入方(店长)", "集团资产管理员"));
    }

    public static RolePair pairOf(BizType biz) {
        RolePair p = MATRIX.get(biz);
        if (p == null) {
            throw new IllegalArgumentException("未登记的业务类型角色对: " + biz);
        }
        return p;
    }
}
