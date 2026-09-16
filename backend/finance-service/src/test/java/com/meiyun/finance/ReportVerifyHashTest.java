package com.meiyun.finance;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P5-B56（L123 第①类）：验真字节口径自节。
 *
 * <p>ReportCsvBuilder 产物本就固定 UTF-8 BOM+CRLF 且 R01/R02 无生成时刻列，
 * 故字节冻结的算法侧契约收敛为三点：①同字节哈希确定且含 BOM 一起参与；
 * ②改一个字节哈希必变（篡改负例）；③文件名锚 createdAt 不锚下载时刻。
 */
class ReportVerifyHashTest {

    @Test
    void sha256_同字节同哈希且为64位小写hex() {
        byte[] a = {0x41, 0x42, 0x43};
        String h1 = ReportCsvBuilder.sha256Hex(a);
        String h2 = ReportCsvBuilder.sha256Hex(a.clone());
        assertEquals(h1, h2);
        assertEquals(64, h1.length());
        assertTrue(h1.matches("[0-9a-f]{64}"));
        // SHA-256("ABC") 标准向量
        assertEquals("b5d4045c3f466fa91fe2cc6abe79232a1a57cdf104f7a26e716e0a1e2789df78", h1);
    }

    @Test
    void sha256_bom参与哈希_篡改任一字节必变() {
        byte[] withBom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'x'};
        byte[] noBom = {'x'};
        assertFalse(ReportCsvBuilder.sha256Hex(withBom).equals(ReportCsvBuilder.sha256Hex(noBom)));

        byte[] tampered = withBom.clone();
        tampered[3] = 'y';
        assertFalse(ReportCsvBuilder.hashEquals(
                ReportCsvBuilder.sha256Hex(withBom), ReportCsvBuilder.sha256Hex(tampered)));
        assertTrue(ReportCsvBuilder.hashEquals(
                ReportCsvBuilder.sha256Hex(withBom), ReportCsvBuilder.sha256Hex(withBom.clone())));
    }

    @Test
    void hashEquals_null与长度不等安全返回false() {
        assertFalse(ReportCsvBuilder.hashEquals(null, "abc"));
        assertFalse(ReportCsvBuilder.hashEquals("abc", null));
        assertFalse(ReportCsvBuilder.hashEquals("abc", "abcd"));
    }

    @Test
    void 文件名锚createdAt_与下载时刻无关() {
        OffsetDateTime created = OffsetDateTime.of(2026, 9, 16, 22, 30, 5, 0, ZoneOffset.ofHours(8));
        String f1 = ReportCsvBuilder.downloadFileName("门店营收日报", "2026-09-15", created);
        String f2 = ReportCsvBuilder.downloadFileName("门店营收日报", "2026-09-15", created);
        assertEquals("门店营收日报-2026-09-15-20260916-223005.csv", f1);
        assertEquals(f1, f2);
        // UTC 存储值也按东八区落名（不依赖容器默认时区）
        OffsetDateTime utc = created.withOffsetSameInstant(ZoneOffset.UTC);
        assertEquals(f1, ReportCsvBuilder.downloadFileName("门店营收日报", "2026-09-15", utc));
    }
}
