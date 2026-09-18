package com.meiyun.customer;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 撞单候选发现纯算法单测：手机号归一化与组类型判定（不起 Spring，SQL/数据域行为靠三轨真验）。
 */
class MergeCandidateServiceTest {

    private DuplicatePhoneRow row(String customerId, String storeCode) {
        return new DuplicatePhoneRow() {
            @Override public String getCustomerId() { return customerId; }
            @Override public String getName() { return "测试客户"; }
            @Override public String getPhone() { return "13800000000"; }
            @Override public String getLevel() { return "普通"; }
            @Override public String getStoreCode() { return storeCode; }
            @Override public String getOwnerStaffId() { return null; }
            @Override public Instant getCreatedAt() { return Instant.parse("2026-09-01T02:00:00Z"); }
        };
    }

    @Test
    void 手机号归一化_去空白连字符括号() {
        assertEquals("13812345678", MergeCandidateService.normalizePhone("138 1234-5678"));
        assertEquals("13812345678", MergeCandidateService.normalizePhone("(138) 1234-5678"));
    }

    @Test
    void 手机号归一化_去国家码前缀() {
        assertEquals("13812345678", MergeCandidateService.normalizePhone("+8613812345678"));
        assertEquals("13812345678", MergeCandidateService.normalizePhone("8613812345678"));
        assertEquals("13812345678", MergeCandidateService.normalizePhone("13812345678"));
    }

    @Test
    void 手机号归一化_null原样() {
        assertNull(MergeCandidateService.normalizePhone(null));
    }

    @Test
    void 组类型_同店为SAME_STORE() {
        assertEquals("SAME_STORE", MergeCandidateService.classifyGroupType(
                List.of(row("SC001", "ST-SH-001"), row("SC002", "ST-SH-001"))));
    }

    @Test
    void 组类型_全公海为POOL() {
        assertEquals("POOL", MergeCandidateService.classifyGroupType(
                List.of(row("SC001", null), row("SC002", null), row("SC003", "  "))));
    }

    @Test
    void 组类型_跨店为CROSS_STORE() {
        assertEquals("CROSS_STORE", MergeCandidateService.classifyGroupType(
                List.of(row("SC001", "ST-SH-001"), row("SC002", "ST-BJ-001"))));
    }

    @Test
    void 组类型_公私海混合为CROSS_STORE() {
        assertEquals("CROSS_STORE", MergeCandidateService.classifyGroupType(
                List.of(row("SC001", "ST-SH-001"), row("SC002", null))));
    }

    @Test
    void 候选掩码_归一化后同号两侧掩码一致() {
        String 带格式 = CustomerService.maskPhone(
                MergeCandidateService.normalizePhone("139 0000-1234"), false);
        String 带国家码 = CustomerService.maskPhone(
                MergeCandidateService.normalizePhone("+8613900001234"), false);
        assertEquals("139****1234", 带格式);
        assertEquals("139****1234", 带国家码);
        assertEquals(带格式, 带国家码);
    }
}
