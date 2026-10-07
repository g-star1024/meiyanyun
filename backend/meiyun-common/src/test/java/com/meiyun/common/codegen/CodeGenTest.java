package com.meiyun.common.codegen;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeGenTest {

    private static final String DAY = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

    @Test
    void nextSeq_emptyHistory_startsFromOne() {
        String no = CodeGen.nextSeq("M", 3, prefix -> null);
        assertEquals("M001", no);
    }

    @Test
    void nextSeq_existingMax_incrementsNumericTail() {
        String no = CodeGen.nextSeq("E", 3, prefix -> "E014");
        assertEquals("E015", no);
    }

    @Test
    void nextSeq_prefixedWithDash_stripsDashForParse() {
        String no = CodeGen.nextSeq("RM-", 3, prefix -> "RM-009");
        assertEquals("RM-010", no);
    }

    @Test
    void nextSeq_unparseableTail_restartsFromOne() {
        String no = CodeGen.nextSeq("E", 3, prefix -> "EXYZ");
        assertEquals("E001", no);
    }

    @Test
    void nextSeq_maxNotMatchingPrefix_startsFromOne() {
        String no = CodeGen.nextSeq("E", 3, prefix -> "SE105");
        assertEquals("E001", no);
    }

    @Test
    void nextSeq_widthPadding_keepsLeadingZeros() {
        String no = CodeGen.nextSeq("HC-", 4, prefix -> "HC-0099");
        assertEquals("HC-0100", no);
    }

    @Test
    void nextDaily_emptyHistory_startsFromOne() {
        String no = CodeGen.nextDaily("CP", like -> null);
        assertEquals("CP" + DAY + "-000001", no);
    }

    @Test
    void nextDaily_existingMax_incrementsLastSixDigits() {
        String no = CodeGen.nextDaily("CP", like -> "CP" + DAY + "-000041");
        assertEquals("CP" + DAY + "-000042", no);
    }

    @Test
    void nextDaily_unparseableTail_restartsFromOne() {
        String no = CodeGen.nextDaily("CP", like -> "CP" + DAY + "-XXXXXX");
        assertEquals("CP" + DAY + "-000001", no);
    }

    @Test
    void nextDaily_likeCondition_passesPrefixAndDay() {
        StringBuilder seen = new StringBuilder();
        CodeGen.nextDaily("CPN", like -> {
            seen.append(like);
            return null;
        });
        assertTrue(seen.toString().startsWith("CPN" + DAY + "-"), "like 条件应为 前缀+当日+-%");
        assertTrue(seen.toString().endsWith("%"), "like 条件应以 % 收尾");
    }
}
