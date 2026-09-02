package com.meiyun.common;

import com.meiyun.common.contraindication.ContraindicationChecker;
import com.meiyun.common.contraindication.ContraindicationChecker.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContraindicationCheckerTest {

    @Test
    void green_when_no_profile() {
        Result r = ContraindicationChecker.check(null, "水光针");
        assertEquals(Level.GREEN, r.level());
        assertTrue(r.hits().isEmpty());
    }

    @Test
    void green_when_clean_profile() {
        Profile p = new Profile(null, null, "否", "否", "否");
        assertEquals(Level.GREEN, ContraindicationChecker.check(p, "热玛吉").level());
    }

    @Test
    void red_on_scar_constitution() {
        Profile p = new Profile(null, null, "是", "否", "否");
        Result r = ContraindicationChecker.check(p, "光子嫩肤");
        assertTrue(r.red());
        assertTrue(r.hits().stream().anyMatch(h -> h.contains("疤痕体质")));
    }

    @Test
    void red_on_pregnancy() {
        Profile p = new Profile(null, null, "否", "是", "否");
        assertTrue(ContraindicationChecker.check(p, "热玛吉").red());
    }

    @Test
    void red_on_coagulation() {
        Profile p = new Profile(null, null, "否", "否", "是");
        assertTrue(ContraindicationChecker.check(p, "热玛吉").red());
    }

    @Test
    void red_when_drug_allergen_in_project() {
        // 青霉素过敏 + 项目名含「青霉素」→ 硬阻断
        Profile p = new Profile("青霉素过敏", "青霉素", "否", "否", "否");
        Result r = ContraindicationChecker.check(p, "青霉素皮试套餐");
        assertTrue(r.red());
        assertTrue(r.hits().stream().anyMatch(h -> h.contains("药物过敏原命中")));
    }

    @Test
    void yellow_when_allergy_history_only() {
        Profile p = new Profile("海鲜过敏", null, "否", "否", "否");
        Result r = ContraindicationChecker.check(p, "水光针");
        assertEquals(Level.YELLOW, r.level());
        assertFalse(r.hits().isEmpty());
    }

    @Test
    void exemption_requires_two_distinct_licensed_signers_and_note() {
        // 缺说明 / 同人 / 非持证 → 各自报错
        assertFalse(ContraindicationChecker.validateExemption("D1", "D2", true, null).isEmpty());
        assertFalse(ContraindicationChecker.validateExemption("D1", "D1", true, "评估通过").isEmpty());
        assertFalse(ContraindicationChecker.validateExemption("D1", "D2", false, "评估通过").isEmpty());
        // 合法豁免
        assertTrue(ContraindicationChecker.validateExemption("D1", "D2", true, "主理医生评估豁免").isEmpty());
    }
}
