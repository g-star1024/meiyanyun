package com.meiyun.common.dualsign;

/**
 * 签署人（系统级硬校验的数据载体）。
 *
 * @param personId      人员唯一标识（用于「两签不同人」校验）
 * @param displayName   展示名
 * @param role          角色名（店长/财务/主理医生/咨询师/前台/治疗师/集团财务总监…）
 * @param roleSequence  岗位序列（用于「不同岗位序列」校验；资产转移例外按 storeId 判定）
 * @param storeId       所属门店（资产转移冲突判定用）
 * @param medicalLicensed 是否持医疗执业资质（医疗类第二签校验）
 */
public record Signer(
        String personId,
        String displayName,
        String role,
        String roleSequence,
        String storeId,
        boolean medicalLicensed
) {
    public Signer {
        if (personId == null || personId.isBlank()) {
            throw new IllegalArgumentException("personId 不可为空");
        }
    }
}
