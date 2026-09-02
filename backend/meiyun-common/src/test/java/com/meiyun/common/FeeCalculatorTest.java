package com.meiyun.common;

import com.meiyun.common.money.FeeCalculator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FeeCalculatorTest {

    @Test
    void default_fee_is_ten_percent() {
        // ¥12,400（1,240,000 分）→ 手续费 124,000 分，退卡 1,116,000 分（TK...003）
        FeeCalculator.FeePlan p = FeeCalculator.compute(1_240_000L, false, false, null);
        assertEquals(124_000L, p.feeCents());
        assertEquals(1_116_000L, p.refundCents());
        assertFalse(p.medicalWaived());
    }

    @Test
    void medical_contraindication_is_fee_free() {
        // TK...005 ¥104,000（10,400,000 分）→ 手续费 0，退卡 = 余额
        FeeCalculator.FeePlan p = FeeCalculator.compute(10_400_000L, true, false, null);
        assertEquals(0L, p.feeCents());
        assertEquals(10_400_000L, p.refundCents());
        assertTrue(p.medicalWaived());
    }

    @Test
    void manual_override_allows_custom_fee() {
        // 操作人决定只扣 ¥10（1,000 分）
        FeeCalculator.FeePlan p = FeeCalculator.compute(1_240_000L, false, true, 1_000L);
        assertEquals(1_000L, p.feeCents());
        assertEquals(1_239_000L, p.refundCents());
        assertTrue(p.manualOverridden());
    }

    @Test
    void manual_override_zero_is_allowed() {
        FeeCalculator.FeePlan p = FeeCalculator.compute(1_240_000L, false, true, 0L);
        assertEquals(0L, p.feeCents());
        assertEquals(1_240_000L, p.refundCents());
    }

    @Test
    void refund_plus_fee_always_equals_balance() {
        FeeCalculator.FeePlan p = FeeCalculator.compute(3_200_000L, false, false, null); // TK...004
        assertEquals(3_200_000L, p.refundCents() + p.feeCents());
        assertEquals(320_000L, p.feeCents()); // 10%
    }

    @Test
    void manual_override_without_amount_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> FeeCalculator.compute(1_240_000L, false, true, null));
    }
}
