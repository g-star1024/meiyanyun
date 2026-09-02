package com.meiyun.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.common.audit.AuditChain;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TreeMap;

/**
 * 审计追加服务。
 * <p>只暴露「追加」语义：计算 prev_hash（链尾 cur_hash 或创世哈希）→ 计算 cur_hash（SHA-256 链）→ 落库。
 * 不提供任何 update / delete，配合数据库账号仅 GRANT INSERT+SELECT 形成双重不可篡改。</p>
 * <p><b>关键：</b>payload 经 PostgreSQL jsonb 存储后会被规范化（key 重排+加空格），
 * 因此写入和验链都必须先对 payload 做 JSON canonicalize（排序 key + 紧凑无空格），
 * 保证哈希计算时用的是同一种字符串表示。</p>
 */
@Service
public class AuditService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public AuditLog append(String bizType, String txnNo, String actor, String action, String payload) {
        if (bizType == null || bizType.isBlank()) throw new IllegalArgumentException("bizType 不可空");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actor 不可空");
        if (action == null || action.isBlank()) throw new IllegalArgumentException("action 不可空");
        if (payload == null || payload.isBlank()) throw new IllegalArgumentException("payload 不可空");

        String prevHash = repository.findFirstByOrderByCreatedAtDesc()
                .map(AuditLog::getCurHash)
                .orElse(AuditChain.genesisHash());

        String canonicalPayload = canonicalize(payload);

        // 时间必须截断到微秒：PostgreSQL timestamptz 列精度为微秒（6 位小数），
        // 而 OffsetDateTime.now() 为纳秒精度（9 位）。若用纳秒原值算哈希，
        // 落库后末 3 位被截断，验链从库里读回微秒值重算必然失配，造成"假性断链"。
        // 截断后"算哈希的时间"与"存库读回的时间"一致。
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        String createdAtIso = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String curHash = AuditChain.computeHash(prevHash, canonicalPayload, actor, action, createdAtIso);

        AuditLog log = new AuditLog(bizType, txnNo, actor, action, canonicalPayload, prevHash, curHash);
        log.setCreatedAt(now);
        return repository.save(log);
    }

    /**
     * 巡检整链：逐条重算 cur_hash 并与存储值比对。任一条失配即返回 brokenAtId。
     */
    public ChainVerifyResult verifyChain() {
        List<AuditLog> chain = repository.findAllByOrderByIdAsc();
        String expectedPrev = AuditChain.genesisHash();
        for (AuditLog node : chain) {
            // 验链时也要对 payload 做 canonicalize（与写入时一致）
            String canonicalPayload = canonicalize(node.getPayload());
            boolean ok = AuditChain.verifyLink(
                    expectedPrev, canonicalPayload, node.getActor(), node.getAction(),
                    node.getCreatedAt().withOffsetSameInstant(ZoneOffset.UTC)
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), node.getCurHash());
            if (!ok) {
                return new ChainVerifyResult(false, node.getId(), chain.size());
            }
            expectedPrev = node.getCurHash();
        }
        return new ChainVerifyResult(true, null, chain.size());
    }

    public record ChainVerifyResult(boolean ok, Long brokenAtId, int total) {
    }

    public List<AuditLog> findAll() {
        return repository.findAllByOrderByIdAsc();
    }

    /**
     * 将 JSON 字符串规范化：解析 → TreeMap 排序 key → 紧凑序列化（无空格）。
     * 确保 PostgreSQL jsonb 读写后字符串一致。
     */
    @SuppressWarnings("unchecked")
    static String canonicalize(String json) {
        try {
            Object parsed = MAPPER.readValue(json, Object.class);
            Object sorted = sortKeys(parsed);
            return MAPPER.writeValueAsString(sorted);
        } catch (JsonProcessingException e) {
            // 非 JSON 则原样返回（兼容纯文本 payload）
            return json;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object sortKeys(Object obj) {
        if (obj instanceof java.util.Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), sortKeys(v)));
            return sorted;
        }
        if (obj instanceof java.util.List<?> list) {
            return list.stream().map(AuditService::sortKeys).toList();
        }
        return obj;
    }
}
