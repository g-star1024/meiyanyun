package com.meiyun.audit;

import com.meiyun.common.audit.AuditChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

/**
 * B50 卡6（L122）：verifyChain 全量断链清单契约（JUnit5 + Mockito）。
 *
 * <p>核心断言：①完整链 ok=true、breaks 空；②存在多处历史断链时必须遍历全程、
 * 沿「存储 curHash」继续推进，独立检出<b>每一处</b>断链（而非首处即返回，
 * 也不把首处之后的正常节点全部误报）；③brokenAtId 保留首处口径兼容旧前端。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceVerifyChainTest {

    @Mock AuditRepository repository;
    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks AuditService service;

    private static final OffsetDateTime T0 =
            OffsetDateTime.of(2026, 9, 11, 8, 0, 0, 0, ZoneOffset.UTC);

    /** 构造一条 n 节点的合法链：prev/cur 哈希按写入规则串联。 */
    private List<AuditLog> buildChain(int n) {
        List<AuditLog> chain = new ArrayList<>();
        String prev = AuditChain.genesisHash();
        for (int i = 1; i <= n; i++) {
            OffsetDateTime ts = T0.plusMinutes(i).truncatedTo(ChronoUnit.MICROS);
            String iso = ts.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            String payload = "{\"i\":" + i + "}";
            String canonical = AuditService.canonicalize(payload);
            String cur = AuditChain.computeHash(prev, canonical, "u" + i, "ACT" + i, iso);
            AuditLog node = new AuditLog("TXN", "T" + i, "u" + i, "ACT" + i, canonical, prev, cur);
            node.setCreatedAt(ts);
            node.setId((long) i);
            chain.add(node);
            prev = cur;
        }
        return chain;
    }

    /**
     * 模拟历史断链（与生产 #380/#520 同型）：篡改某节点 payload 后用伪造 payload 重算其
     * cur_hash，并把下一节点 prev_hash 同步为伪造值——即篡改者把断点之后重新接成自洽链。
     * 验链期望前驱仍为原始上游 cur_hash，因此恰好只在该节点报一次断链。
     */
    private void tamperPayloadAndRechain(List<AuditLog> chain, int index, String tamperedPayload) {
        AuditLog node = chain.get(index);
        String iso = node.getCreatedAt().withOffsetSameInstant(ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String bogusCur = AuditChain.computeHash(node.getPrevHash(), tamperedPayload,
                node.getActor(), node.getAction(), iso);
        node.setPayload(tamperedPayload);
        node.setCurHash(bogusCur);
        if (index + 1 < chain.size()) {
            chain.get(index + 1).setPrevHash(bogusCur);
        }
    }

    @Test
    void intactChain_okAndNoBreaks() {
        List<AuditLog> chain = buildChain(5);
        when(repository.findAllByOrderByIdAsc()).thenReturn(chain);

        AuditService.ChainVerifyResult result = service.verifyChain();

        assertTrue(result.ok());
        assertNull(result.brokenAtId());
        assertEquals(5, result.total());
        assertTrue(result.breaks().isEmpty());
    }

    @Test
    void twoHistoricalBreaks_areBothDetectedIndependently() {
        List<AuditLog> chain = buildChain(6);
        // 篡改节点 2/5 的 payload 并重链接续，失配分别在下游节点 3/6 的 prev_hash 处暴露
        tamperPayloadAndRechain(chain, 1, "{\"i\":2,\"tampered\":true}");
        tamperPayloadAndRechain(chain, 4, "{\"i\":5,\"tampered\":true}");
        when(repository.findAllByOrderByIdAsc()).thenReturn(chain);

        AuditService.ChainVerifyResult result = service.verifyChain();

        assertFalse(result.ok());
        assertEquals(2, result.breaks().size(), "两处历史断链都必须被独立检出");
        assertEquals(List.of(3L, 6L), result.breaks().stream().map(AuditService.ChainBreak::id).toList());
        assertEquals(3L, result.brokenAtId(), "brokenAtId 保留首处口径");
        assertEquals(6, result.total());
    }

    @Test
    void singleBreak_doesNotFalseReportFollowingNodes() {
        List<AuditLog> chain = buildChain(4);
        tamperPayloadAndRechain(chain, 1, "{\"i\":2,\"tampered\":true}");
        when(repository.findAllByOrderByIdAsc()).thenReturn(chain);

        AuditService.ChainVerifyResult result = service.verifyChain();

        assertEquals(1, result.breaks().size());
        assertEquals(3L, result.breaks().get(0).id());
        assertEquals(chain.get(2).getPrevHash(), result.breaks().get(0).storedPrev());
    }
}
