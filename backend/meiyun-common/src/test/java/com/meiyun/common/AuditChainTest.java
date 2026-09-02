package com.meiyun.common;

import com.meiyun.common.audit.AuditChain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuditChainTest {

    @Test
    void hash_is_deterministic_and_64_hex() {
        String h1 = AuditChain.computeHash(AuditChain.genesisHash(), "{\"x\":1}", "u1", "CREATE", "2026-08-20T10:00:00Z");
        String h2 = AuditChain.computeHash(AuditChain.genesisHash(), "{\"x\":1}", "u1", "CREATE", "2026-08-20T10:00:00Z");
        assertEquals(h1, h2);
        assertEquals(64, h1.length());
    }

    @Test
    void verify_link_matches_for_correct_input() {
        String prev = AuditChain.genesisHash();
        String cur = AuditChain.computeHash(prev, "payload", "u1", "SIGN", "2026-08-20T10:00:00Z");
        assertTrue(AuditChain.verifyLink(prev, "payload", "u1", "SIGN", "2026-08-20T10:00:00Z", cur));
    }

    @Test
    void tampered_payload_breaks_chain() {
        String prev = AuditChain.genesisHash();
        String cur = AuditChain.computeHash(prev, "payload", "u1", "SIGN", "2026-08-20T10:00:00Z");
        assertFalse(AuditChain.verifyLink(prev, "payload-TAMPERED", "u1", "SIGN", "2026-08-20T10:00:00Z", cur));
    }

    @Test
    void chain_of_three_stays_consistent_only_when_unaltered() {
        String h0 = AuditChain.genesisHash();
        String h1 = AuditChain.computeHash(h0, "a", "u1", "CREATE", "t1");
        String h2 = AuditChain.computeHash(h1, "b", "u2", "UPDATE", "t2");
        assertTrue(AuditChain.verifyLink(h0, "a", "u1", "CREATE", "t1", h1));
        assertTrue(AuditChain.verifyLink(h1, "b", "u2", "UPDATE", "t2", h2));
        // 篡改 h1 的 payload 会让 h2 失配
        assertFalse(AuditChain.verifyLink(h1, "b-TAMPERED", "u2", "UPDATE", "t2", h2));
    }
}
