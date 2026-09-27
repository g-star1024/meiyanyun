package com.meiyun.org.integration;

import com.meiyun.org.audit.AuditRecorder;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * T3 数据中台 连接器目录领域服务（DESIGN-T3 §四 端点 #1-#5，T3-B1）。
 *
 * <p>红线（DESIGN-T3 §二）：①单向镜像绝不反向写资金池；②绝不假装已连通——status 仅由
 * 真实探测/同步驱动，CONNECTED 只能靠 test 真实探测达成，探测失败如实落 ERROR＋last_error；
 * ③只建链路本体不伪造三方对接。
 *
 * <p>安全口径：credential_key 仅存凭证引用名掩码（密钥本体在 external_integration 单点持钥）；
 * 审计只记 code/动作/结果标志，绝不记密钥与完整请求体。
 */
@Service
public class IntegrationConnectorService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationConnectorService.class);

    private static final List<String> TYPES = List.of(
            IntegrationConnector.TYPE_PAYMENT, IntegrationConnector.TYPE_INSURANCE,
            IntegrationConnector.TYPE_WECOM, IntegrationConnector.TYPE_TAX,
            IntegrationConnector.TYPE_ADS, IntegrationConnector.TYPE_KINGDEE,
            IntegrationConnector.TYPE_YONYOU);

    private static final int MAX_LOG_LIMIT = 500;
    private static final int DEFAULT_LOG_LIMIT = 100;

    private final IntegrationConnectorRepository connectorRepo;
    private final IntegrationCallLogRepository callLogRepo;
    private final AuditRecorder audit;
    /** 测试连接专用：连接 3s/读取 3s（DESIGN-T3 §四 #4），不动 OrgApplication 全局 Bean（3s/5s）。 */
    private final RestTemplate probeTemplate;

    public IntegrationConnectorService(IntegrationConnectorRepository connectorRepo,
                                       IntegrationCallLogRepository callLogRepo,
                                       AuditRecorder audit) {
        this.connectorRepo = connectorRepo;
        this.callLogRepo = callLogRepo;
        this.audit = audit;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.probeTemplate = new RestTemplate(factory);
    }

    // ==================== 目录视图 ====================

    /** 目录全量视图：credential_key 掩码回显；24h 调用/失败计数取 call_log 真实聚合（不用假数）。 */
    @Transactional(readOnly = true)
    public List<ConnectorView> listConnectors() {
        Map<Long, long[]> stats = new HashMap<>();
        for (Object[] row : callLogRepo.count24hByConnector(OffsetDateTime.now().minusHours(24))) {
            long total = ((Number) row[1]).longValue();
            long errors = row[2] == null ? 0 : ((Number) row[2]).longValue();
            stats.put((Long) row[0], new long[]{total, errors});
        }
        return connectorRepo.findAll().stream()
                .map(e -> toView(e, stats.getOrDefault(e.getId(), new long[]{0, 0})))
                .toList();
    }

    private ConnectorView toView(IntegrationConnector e, long[] stat) {
        return new ConnectorView(e.getId(), e.getCode(), e.getType(), e.getName(), e.getEndpoint(),
                e.getCredentialKey(), e.getStatus(), "UNIDIRECTIONAL",
                e.getLastSyncAt(), e.getLastError(), stat[0], stat[1], e.getCreatedAt());
    }

    // ==================== 新建 ====================

    /**
     * 新建连接器：code 唯一（重复 409）；type 七枚举；name 必填；
     * endpoint 须 https，http 需 forceInsecure=true 二次确认（否则 422，照 B57 卡2 美团采纳项）。
     * 初始 status=DISCONNECTED 诚实态；审计 CONNECTOR_CREATE。
     */
    @Transactional
    public ConnectorView create(CreateRequest req, String actor) {
        String code = req.code() == null ? "" : req.code().trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("连接器编码必填");
        }
        if (connectorRepo.existsByCode(code)) {
            throw new Conflict("连接器编码已存在: " + code);
        }
        String type = req.type() == null ? "" : req.type().trim();
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("连接器类型非法，须为 " + String.join("/", TYPES));
        }
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("连接器名称必填");
        }
        String endpoint = assertHttpsEndpoint(req.endpoint(), Boolean.TRUE.equals(req.forceInsecure()));

        IntegrationConnector e = new IntegrationConnector();
        e.setCode(code);
        e.setType(type);
        e.setName(name);
        e.setEndpoint(endpoint);
        e.setCredentialKey(maskCredential(req.credentialKey()));
        e.setStatus(IntegrationConnector.STATUS_DISCONNECTED);
        OffsetDateTime now = OffsetDateTime.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        IntegrationConnector saved = connectorRepo.save(e);

        audit.record("INTEGRATION", code, actor, "CONNECTOR_CREATE",
                "{\"code\":\"" + code + "\",\"type\":\"" + type + "\"}");
        return toView(saved, new long[]{0, 0});
    }

    // ==================== 更新 ====================

    /** name/endpoint/credential_key 可改（endpoint 同 https 校验）；审计 CONNECTOR_UPDATE。 */
    @Transactional
    public ConnectorView update(Long id, UpdateRequest req, String actor) {
        IntegrationConnector e = connectorRepo.findById(id)
                .orElseThrow(() -> new NotFound("连接器不存在"));
        boolean changed = false;
        if (req.name() != null) {
            String name = req.name().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("连接器名称必填");
            }
            e.setName(name);
            changed = true;
        }
        if (req.endpoint() != null) {
            e.setEndpoint(assertHttpsEndpoint(req.endpoint(), Boolean.TRUE.equals(req.forceInsecure())));
            changed = true;
        }
        if (req.credentialKey() != null) {
            e.setCredentialKey(maskCredential(req.credentialKey()));
            changed = true;
        }
        e.setUpdatedAt(OffsetDateTime.now());
        IntegrationConnector saved = connectorRepo.save(e);
        audit.record("INTEGRATION", e.getCode(), actor, "CONNECTOR_UPDATE",
                "{\"code\":\"" + e.getCode() + "\",\"changed\":" + changed + "}");
        return toView(saved, new long[]{0, 0});
    }

    // ==================== 测试连接（真实探测） ====================

    /**
     * 真实探测（DESIGN-T3 §四 #4）：GET endpoint 连接 3s/读取 3s——
     * 2xx/3xx/4xx（TCP+HTTP 可达）→ CONNECTED 且 last_error 清；
     * 5xx/网络异常 → ERROR＋last_error 如实。逐次落 call_log（transaction_id=TEST-{id}-{ts}）＋审计 CONNECTOR_TEST。
     */
    @Transactional
    public TestConnectorResult test(Long id, String actor) {
        IntegrationConnector e = connectorRepo.findById(id)
                .orElseThrow(() -> new NotFound("连接器不存在"));

        long start = System.currentTimeMillis();
        int statusCode = 0;
        boolean reachable;
        String errorMsg = null;
        try {
            var response = probeTemplate.getForEntity(e.getEndpoint(), String.class);
            statusCode = response.getStatusCode().value();
            reachable = true;
        } catch (HttpClientErrorException ex) {
            statusCode = ex.getStatusCode().value();
            reachable = true;
        } catch (HttpServerErrorException ex) {
            statusCode = ex.getStatusCode().value();
            reachable = false;
            errorMsg = "三方服务异常(" + statusCode + ")";
        } catch (Exception ex) {
            reachable = false;
            errorMsg = "网关不可达/超时：" + ex.getMessage();
        }
        int latency = (int) (System.currentTimeMillis() - start);

        OffsetDateTime now = OffsetDateTime.now();
        if (reachable) {
            e.setStatus(IntegrationConnector.STATUS_CONNECTED);
            e.setLastError(null);
        } else {
            e.setStatus(IntegrationConnector.STATUS_ERROR);
            e.setLastError(errorMsg == null ? "探测失败" : truncate(errorMsg, 250));
        }
        e.setUpdatedAt(now);
        connectorRepo.save(e);

        IntegrationCallLog entry = new IntegrationCallLog();
        entry.setConnectorId(e.getId());
        entry.setTransactionId("TEST-" + e.getId() + "-" + System.currentTimeMillis());
        entry.setDirection(IntegrationCallLog.DIRECTION_OUT);
        entry.setMethod("GET");
        entry.setEndpoint(e.getEndpoint());
        entry.setStatusCode(statusCode);
        entry.setLatencyMs(latency);
        entry.setStatus(reachable ? IntegrationCallLog.STATUS_ACK : IntegrationCallLog.STATUS_FAIL);
        entry.setErrorMsg(reachable ? null : truncate(errorMsg, 250));
        entry.setRequestAt(now);
        callLogRepo.save(entry);

        audit.record("INTEGRATION", e.getCode(), actor, "CONNECTOR_TEST",
                "{\"code\":\"" + e.getCode() + "\",\"reachable\":" + reachable
                        + ",\"statusCode\":" + statusCode + "}");

        String message = reachable
                ? "探测可达（HTTP " + statusCode + "），连接器已置 CONNECTED"
                : "探测失败：" + errorMsg + "，连接器已置 ERROR";
        log.info("连接器测试 code={} reachable={} statusCode={} latency={}ms", e.getCode(), reachable, statusCode, latency);
        return new TestConnectorResult(reachable, e.getStatus(), statusCode, latency, message);
    }

    // ==================== 调用日志 ====================

    /** 调用日志：request_at 倒序；connectorId 可选；limit 默认 100 上限 500。 */
    @Transactional(readOnly = true)
    public List<CallLogView> listLogs(Long connectorId, Integer limit) {
        int size = limit == null || limit <= 0 ? DEFAULT_LOG_LIMIT : Math.min(limit, MAX_LOG_LIMIT);
        List<IntegrationCallLog> rows = connectorId == null
                ? callLogRepo.findAllByOrderByRequestAtDesc(PageRequest.of(0, size))
                : callLogRepo.findByConnectorIdOrderByRequestAtDesc(connectorId, PageRequest.of(0, size));
        Map<Long, String> names = new HashMap<>();
        return rows.stream().map(l -> {
            String connectorName = names.computeIfAbsent(l.getConnectorId(),
                    cid -> connectorRepo.findById(cid).map(IntegrationConnector::getName).orElse("未知连接器"));
            return new CallLogView(l.getId(), l.getConnectorId(), connectorName, l.getTransactionId(),
                    l.getDirection(), l.getMethod(), l.getEndpoint(), l.getStatusCode(),
                    l.getLatencyMs(), l.getStatus(), l.getRequestAt(), l.getErrorMsg());
        }).toList();
    }

    // ==================== 校验与工具 ====================

    /** endpoint 须合法 http(s) URL；非 https 须 forceInsecure=true（否则 422）。 */
    private static String assertHttpsEndpoint(String raw, boolean forceInsecure) {
        String endpoint = raw == null ? "" : raw.trim();
        if (endpoint.isEmpty()) {
            throw new IllegalArgumentException("三方 API 地址必填");
        }
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (Exception ex) {
            throw new IllegalArgumentException("三方 API 地址不是合法 URL: " + endpoint);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("三方 API 地址必须是合法的 http(s) URL");
        }
        if ("http".equalsIgnoreCase(scheme) && !forceInsecure) {
            throw new InsecureEndpoint("明文 HTTP 地址仅限内网联调，须 forceInsecure=true 二次确认后才能保存");
        }
        return endpoint;
    }

    /** 凭证引用名：只存掩码（前4****后4）；空入参返回 null；已含 **** 视为掩码原样保留。 */
    private static String maskCredential(String credentialKey) {
        if (credentialKey == null || credentialKey.isBlank()) {
            return null;
        }
        String trimmed = credentialKey.trim();
        if (trimmed.contains("****")) {
            return trimmed;
        }
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 4) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    private static String truncate(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }

    // ==================== 读模型/入参/异常 ====================

    public record ConnectorView(Long id, String code, String type, String name, String endpoint,
                                String credentialKey, String status, String syncMode,
                                OffsetDateTime lastSyncAt, String lastError,
                                long callCount24h, long errorCount24h, OffsetDateTime createdAt) {
    }

    public record CreateRequest(String code, String type, String name, String endpoint,
                                String credentialKey, Boolean forceInsecure) {
    }

    public record UpdateRequest(String name, String endpoint, String credentialKey, Boolean forceInsecure) {
    }

    public record TestConnectorResult(boolean reachable, String status, int statusCode, int latencyMs,
                                      String message) {
    }

    public record CallLogView(Long id, Long connectorId, String connectorName, String transactionId,
                              String direction, String method, String endpoint, Integer statusCode,
                              Integer latencyMs, String status, OffsetDateTime requestAt, String errorMsg) {
    }

    public static class NotFound extends RuntimeException {
        public NotFound(String m) {
            super(m);
        }
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String m) {
            super(m);
        }
    }

    /** 非 https 且未二次确认（映射 422）。 */
    public static class InsecureEndpoint extends RuntimeException {
        public InsecureEndpoint(String m) {
            super(m);
        }
    }
}
