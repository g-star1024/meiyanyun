package com.meiyun.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.common.audit.AuditChain;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final JdbcTemplate jdbcTemplate;

    public AuditService(AuditRepository repository, JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public AuditLog append(String bizType, String txnNo, String actor, String action, String payload) {
        if (bizType == null || bizType.isBlank()) throw new IllegalArgumentException("bizType 不可空");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actor 不可空");
        if (action == null || action.isBlank()) throw new IllegalArgumentException("action 不可空");
        if (payload == null || payload.isBlank()) throw new IllegalArgumentException("payload 不可空");

        // 追加串行化：HTTP 直发与 outbox 中继可能并发，二者都先读链尾再落库，
        // 不加锁会读到同一链尾造成 prev_hash 分叉（验链断链）。事务级 advisory 锁随提交自动释放。
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(hashtext('meiyun_audit_append'))");

        String prevHash = repository.findFirstByOrderByIdDesc()
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
     * 巡检整链：逐条重算 cur_hash 并与存储值比对，<b>遍历全程</b>收集所有失配节点。
     * <p>关键：发现失配后不能沿重算值继续（否则历史断链会导致其后全部节点误报），
     * expectedPrev 始终推进为「存储的 node.curHash」，从而独立检出每一处断链。
     * brokenAtId 保留首处口径以兼容旧前端，全量清单见 breaks。</p>
     */
    public ChainVerifyResult verifyChain() {
        List<AuditLog> chain = repository.findAllByOrderByIdAsc();
        List<ChainBreak> breaks = new ArrayList<>();
        String expectedPrev = AuditChain.genesisHash();
        for (AuditLog node : chain) {
            // 验链时也要对 payload 做 canonicalize（与写入时一致）
            String canonicalPayload = canonicalize(node.getPayload());
            String createdAtIso = node.getCreatedAt().withOffsetSameInstant(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            boolean ok = AuditChain.verifyLink(
                    expectedPrev, canonicalPayload, node.getActor(), node.getAction(),
                    createdAtIso, node.getCurHash());
            if (!ok) {
                breaks.add(new ChainBreak(node.getId(), expectedPrev, node.getPrevHash(),
                        node.getCurHash(), node.getAction(), node.getActor(), createdAtIso));
            }
            // 无论是否失配都沿「存储值」推进，保证后续断链可被独立检出
            expectedPrev = node.getCurHash();
        }
        Long firstBreak = breaks.isEmpty() ? null : breaks.get(0).id();
        return new ChainVerifyResult(breaks.isEmpty(), firstBreak, chain.size(), breaks);
    }

    /**
     * 单处断链详情（供前端全量清单展示与对账排查）。
     *
     * @param id           失配节点 audit_log.id
     * @param expectedPrev 验链期望的前驱哈希（链首为创世哈希）
     * @param storedPrev   该节点存储的 prev_hash
     * @param curHash      该节点存储的 cur_hash（与重算值不一致）
     * @param action       动作（排查辅助）
     * @param actor        操作人（排查辅助）
     * @param createdAt    UTC ISO 时间（排查辅助）
     */
    public record ChainBreak(Long id, String expectedPrev, String storedPrev, String curHash,
                             String action, String actor, String createdAt) {
    }

    public record ChainVerifyResult(boolean ok, Long brokenAtId, int total, List<ChainBreak> breaks) {
    }

    public List<AuditLog> findAll() {
        return repository.findAllByOrderByIdAsc();
    }

    /**
     * 审计日志分页检索（M1 集团审计日志页）。
     * <p>过滤：bizType/actor 精确匹配，created_at 时间范围，keyword 模糊匹配
     * txn_no/action/actor/payload（jsonb cast 为文本）。按 id 倒序（最新在前）。</p>
     */
    public PageResult search(String bizType, String actor, String keyword,
                             OffsetDateTime from, OffsetDateTime to, int page, int size) {
        int p = Math.max(page, 0);
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Specification<AuditLog> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (bizType != null && !bizType.isBlank()) {
                ps.add(cb.equal(root.get("bizType"), bizType.trim()));
            }
            if (actor != null && !actor.isBlank()) {
                ps.add(cb.equal(root.get("actor"), actor.trim()));
            }
            if (from != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("txnNo")), like),
                        cb.like(cb.lower(root.get("action")), like),
                        cb.like(cb.lower(root.get("actor")), like),
                        cb.like(cb.lower(root.get("payload").as(String.class)), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<AuditLog> result = repository.findAll(spec,
                PageRequest.of(p, s, Sort.by("id").descending()));
        return new PageResult(result.getContent(), result.getTotalElements(), p, s);
    }

    /** 稳定分页返回体（不直出 Spring Page，避免序列化结构随版本漂移）。 */
    public record PageResult(List<AuditLog> items, long total, int page, int size) {
    }

    /** 审计统计面：总数/近 24h/操作人数/模块分布（页面 KPI 卡与模块过滤器同源）。 */
    public Map<String, Object> facets() {
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log", Long.class);
        Long last24 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE created_at >= now() - interval '24 hours'", Long.class);
        Long actors = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT actor) FROM audit_log", Long.class);
        List<Map<String, Object>> bizTypes = jdbcTemplate.queryForList(
                "SELECT biz_type AS \"bizType\", count(*) AS \"count\""
                        + " FROM audit_log GROUP BY biz_type ORDER BY count(*) DESC, biz_type");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total == null ? 0 : total);
        m.put("last24", last24 == null ? 0 : last24);
        m.put("actors", actors == null ? 0 : actors);
        m.put("bizTypes", bizTypes);
        return m;
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
