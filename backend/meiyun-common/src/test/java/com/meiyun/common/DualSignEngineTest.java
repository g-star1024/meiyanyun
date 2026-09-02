package com.meiyun.common;

import com.meiyun.common.dualsign.BizType;
import com.meiyun.common.dualsign.DualSignEngine;
import com.meiyun.common.dualsign.DualSignException;
import com.meiyun.common.dualsign.SignRequest;
import com.meiyun.common.dualsign.Signer;
import com.meiyun.common.dualsign.SignTier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DualSignEngineTest {

    private static Signer signer(String id, String role, String seq, boolean medical) {
        return new Signer(id, "name-" + id, role, seq, "ST-SH-001", medical);
    }

    @Test
    void refund_l1_should_be_promoted_to_l2_by_brbl013() {
        // RF...007 ¥8,800（880000 分）落在 L1 单签区间，但退款属出账类 → 抬升 L2
        assertEquals(SignTier.L2, DualSignEngine.computeTier(BizType.REFUND, 880_000L));
        assertEquals(2, DualSignEngine.requiredSignCount(BizType.REFUND, 880_000L));
    }

    @Test
    void refund_28k_is_l2() {
        assertEquals(SignTier.L2, DualSignEngine.computeTier(BizType.REFUND, 2_800_000L));
    }

    @Test
    void refund_112k_is_l3() {
        assertEquals(SignTier.L3, DualSignEngine.computeTier(BizType.REFUND, 11_200_000L));
        assertEquals(3, DualSignEngine.requiredSignCount(BizType.REFUND, 11_200_000L));
    }

    @Test
    void card_cancel_medical_104k_is_l3() {
        // TK...005 ¥104,000（10,400,000 分）≥ ¥100,000 → L3
        assertEquals(SignTier.L3, DualSignEngine.computeTier(BizType.CARD_CANCEL, 10_400_000L));
    }

    @Test
    void valid_dual_sign_passes() {
        SignRequest req = new SignRequest(
                BizType.REFUND, 880_000L,
                signer("u1", "初审人(店长)", "店长序列", false),
                signer("u2", "复核人(财务)", "财务序列", false),
                null);
        assertDoesNotThrow(() -> DualSignEngine.validate(req));
    }

    @Test
    void same_person_rejected() {
        Signer s = signer("u1", "初审人(店长)", "店长序列", false);
        SignRequest req = new SignRequest(BizType.REFUND, 880_000L, s, s, null);
        DualSignException ex = assertThrows(DualSignException.class, () -> DualSignEngine.validate(req));
        assertTrue(ex.getViolations().stream().anyMatch(v -> v.contains("同一人")));
    }

    @Test
    void same_role_sequence_rejected() {
        SignRequest req = new SignRequest(
                BizType.REFUND, 880_000L,
                signer("u1", "店长A", "店长序列", false),
                signer("u2", "店长B", "店长序列", false),
                null);
        DualSignException ex = assertThrows(DualSignException.class, () -> DualSignEngine.validate(req));
        assertTrue(ex.getViolations().stream().anyMatch(v -> v.contains("岗位序列")));
    }

    @Test
    void medical_second_sign_requires_license() {
        SignRequest req = new SignRequest(
                BizType.SCRAP, 500_000L, // 报损强制双签，医疗类
                signer("u1", "报损人(治疗师)", "治疗师序列", false),
                signer("u2", "复核人(主理医生)", "医疗序列", false), // 无执业资质
                null);
        DualSignException ex = assertThrows(DualSignException.class, () -> DualSignEngine.validate(req));
        assertTrue(ex.getViolations().stream().anyMatch(v -> v.contains("医疗执业资质")));
    }

    @Test
    void medical_second_sign_with_license_passes() {
        SignRequest req = new SignRequest(
                BizType.SCRAP, 500_000L,
                signer("u1", "报损人(治疗师)", "治疗师序列", false),
                signer("u2", "复核人(主理医生)", "医疗序列", true),
                null);
        assertDoesNotThrow(() -> DualSignEngine.validate(req));
    }

    @Test
    void asset_transfer_same_role_diff_store_ok() {
        Signer s1 = new Signer("u1", "调出店长", "店长序列", "ST-SH-001", "ST-SH-001", false);
        Signer s2 = new Signer("u2", "调入店长", "店长序列", "ST-BJ-002", "ST-BJ-002", false);
        SignRequest req = new SignRequest(BizType.ASSET_TRANSFER, 50_000_000L, s1, s2,
                signer("u3", "集团资产管理员", "集团序列", false));
        assertDoesNotThrow(() -> DualSignEngine.validate(req));
    }

    @Test
    void asset_transfer_same_role_same_store_rejected() {
        Signer s1 = new Signer("u1", "调出店长", "店长序列", "ST-SH-001", "ST-SH-001", false);
        Signer s2 = new Signer("u2", "调入店长", "店长序列", "ST-SH-001", "ST-SH-001", false);
        SignRequest req = new SignRequest(BizType.ASSET_TRANSFER, 50_000_000L, s1, s2,
                signer("u3", "集团资产管理员", "集团序列", false));
        DualSignException ex = assertThrows(DualSignException.class, () -> DualSignEngine.validate(req));
        assertTrue(ex.getViolations().stream().anyMatch(v -> v.contains("同门店")));
    }

    @Test
    void l3_requires_third_signer() {
        SignRequest req = new SignRequest(
                BizType.REFUND, 11_200_000L,
                signer("u1", "初审人(店长)", "店长序列", false),
                signer("u2", "复核人(财务)", "财务序列", false),
                null); // 缺第三签
        DualSignException ex = assertThrows(DualSignException.class, () -> DualSignEngine.validate(req));
        assertTrue(ex.getViolations().stream().anyMatch(v -> v.contains("第三签")));
    }
}
