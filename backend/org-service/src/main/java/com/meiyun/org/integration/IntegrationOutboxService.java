package com.meiyun.org.integration;

import com.meiyun.org.audit.AuditRecorder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * T3 数据中台 Outbox 单向镜像引擎＋T+1 对账（DESIGN-T3 §四 端点 #6-#10，T3-B2）。
 *
 * <p>红线（DESIGN-T3 §二）：①单向镜像——只从 txn 已支付单向连接器外推镜像，
 * 绝不反向写资金池；②绝不假装已连通——真实外呼 2xx 才置 ACK/remote_ack=true，
 * 其余一律 FAILED＋error_msg 如实，禁止伪造成功/伪造长短款。
 *
 * <p>幂等双锚：uk(connector_id, txn_no) 同步重放不重复落库不重复外呼；
 * uk(connector_id, biz_date) 对账重放返既有批次。retry 复用 transaction_id=txn_no
 * 更新既有 call_log 行（一事务一日志行，不顶 uk 锚）。
 *
 * <p>对账本地口径：matched=remote_ack=true 置 MATCHED；long/short/diff 恒 0 如实
 * （三方账单文件导入解析留 DESIGN-T3 §7 移交）。
 */
@Service
public class IntegrationOutboxService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationOutboxService.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_LIST_LIMIT = 500;
    private static final int DEFAULT_LIST_LIMIT = 100;
    /** 单次同步处理上限（防 txn 侧大窗口拉单打爆外呼链路；超出截断如实计 truncated）。 */
    private static final int MAX_SYNC_PROCESS = 200;

    private final IntegrationConnectorRepository connectorRepo;
    private final IntegrationOutboxRepository outboxRepo;
    private final IntegrationReconcileBatchRepository batchRepo;
    private final IntegrationCallLogRepository callLogRepo;
    private final TxnPaidOrderClient paidOrderClient;
    private final AuditRecorder audit;
    /** 外呼专用：连接 3s/读取 3s（DESIGN-T3 §四 #6），不动 OrgApplication 全局 Bean（3s/5s）。 */
    private final RestTemplate pushTemplate;

    public IntegrationOutboxService(IntegrationConnectorRepository connectorRepo,
                                    IntegrationOutboxRepository outboxRepo,
                                    IntegrationReconcileBatchRepository batchRepo,
                                    IntegrationCallLogRepository callLogRepo,
                                    TxnPaidOrderClient paidOrderClient,
                                    AuditRecorder audit) {
        this.connectorRepo = connectorRepo;
        this.outboxRepo = outboxRepo;
        this.batchRepo = batchRepo;
        this.callLogRepo = callLogRepo;
        this.paidOrderClient = paidOrderClient;
        this.audit = audit;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.pushTemplate = new RestTemplate(factory);
    }

    // ==================== #6 单向镜像同步 ====================

    /**
     * 同步引擎：txn 内部端点拉已支付单（北京昨日~今日双日窗口，覆盖 T+0/T+1 边缘）
     * → uk 幂等落 outbox（已存在跳过不重复外呼）→ PENDING 逐条真实外呼 POST endpoint
     * （body=镜像 JSON，3s 超时）→ 2xx→ACK/remote_ack=true，其余→FAILED＋error_msg；
     * 逐条落 call_log（transaction_id=txn_no）；连接器 last_sync_at 刷新；审计 OUTBOX_SYNC。
     */
    @Transactional
    public SyncResult sync(Long connectorId, String actor) {
        IntegrationConnector connector = connectorRepo.findById(connectorId)
                .orElseThrow(() -> new NotFound("连接器不存在"));

        LocalDate today = LocalDate.now(ZONE);
        List<TxnPaidOrderClient.PaidOrderView> pulled = paidOrderClient.fetchPaidOrders(today.minusDays(1), today);

        int created = 0;
        int skipped = 0;
        int ack = 0;
        int failed = 0;
        int truncated = 0;
        String lastError = null;
        OffsetDateTime now = OffsetDateTime.now();

        for (int i = 0; i < pulled.size(); i++) {
            TxnPaidOrderClient.PaidOrderView order = pulled.get(i);
            if (order.orderNo() == null || order.orderNo().isBlank()) {
                continue;
            }
            if (outboxRepo.existsByConnectorIdAndTxnNo(connector.getId(), order.orderNo())) {
                skipped++;
                continue;
            }
            if (created >= MAX_SYNC_PROCESS) {
                truncated++;
                continue;
            }
            IntegrationOutbox msg = new IntegrationOutbox();
            msg.setOutboxNo(nextOutboxNo());
            msg.setConnectorId(connector.getId());
            msg.setBizType(IntegrationOutbox.BIZ_ORDER_PAY);
            msg.setTxnNo(order.orderNo());
            msg.setAmount(order.amount() == null ? null
                    : BigDecimal.valueOf(order.amount()).movePointLeft(2));
            msg.setLocalSent(true);
            msg.setRemoteAck(false);
            msg.setReconciled(false);
            msg.setStatus(IntegrationOutbox.STATUS_PENDING);
            msg.setOccurredAt(order.createdAt() == null ? now : order.createdAt());
            outboxRepo.save(msg);
            created++;

            PushOutcome outcome = push(connector, msg);
            if (outcome.ok()) {
                ack++;
            } else {
                failed++;
                lastError = outcome.errorMsg();
            }
        }

        connector.setLastSyncAt(OffsetDateTime.now());
        if (failed > 0) {
            connector.setLastError(truncate("同步外呼失败 " + failed + " 条（最近：" + lastError + "）", 250));
        } else if (created > 0) {
            connector.setLastError(null);
        }
        connector.setUpdatedAt(OffsetDateTime.now());
        connectorRepo.save(connector);

        audit.record("INTEGRATION", connector.getCode(), actor, "OUTBOX_SYNC",
                "{\"code\":\"" + connector.getCode() + "\",\"pulled\":" + pulled.size()
                        + ",\"created\":" + created + ",\"skipped\":" + skipped
                        + ",\"ack\":" + ack + ",\"failed\":" + failed
                        + ",\"truncated\":" + truncated + "}");
        log.info("Outbox 同步 code={} pulled={} created={} skipped={} ack={} failed={} truncated={}",
                connector.getCode(), pulled.size(), created, skipped, ack, failed, truncated);

        String message = "拉取已支付单 " + pulled.size() + " 条：新落 " + created + "（ACK " + ack
                + " / FAILED " + failed + "），幂等跳过 " + skipped
                + (truncated > 0 ? "，超上限截断 " + truncated : "");
        return new SyncResult(pulled.size(), created, skipped, ack, failed, truncated, message);
    }

    // ==================== #7 Outbox 列表 ====================

    /** occurred_at 倒序；status/connectorId 可选过滤；limit 默认 100 上限 500。 */
    @Transactional(readOnly = true)
    public List<OutboxView> listOutbox(String status, Long connectorId, Integer limit) {
        int size = limit == null || limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
        String st = status == null || status.isBlank() ? null : status.trim();
        List<IntegrationOutbox> rows;
        if (st != null && connectorId != null) {
            rows = outboxRepo.findByConnectorIdAndStatusOrderByOccurredAtDesc(connectorId, st, PageRequest.of(0, size));
        } else if (st != null) {
            rows = outboxRepo.findByStatusOrderByOccurredAtDesc(st, PageRequest.of(0, size));
        } else if (connectorId != null) {
            rows = outboxRepo.findByConnectorIdOrderByOccurredAtDesc(connectorId, PageRequest.of(0, size));
        } else {
            rows = outboxRepo.findAllByOrderByOccurredAtDesc(PageRequest.of(0, size));
        }
        Map<Long, String> names = new HashMap<>();
        return rows.stream().map(o -> new OutboxView(o.getId(), o.getOutboxNo(), o.getConnectorId(),
                connectorName(names, o.getConnectorId()), o.getBizType(), o.getTxnNo(), o.getAmount(),
                o.getStatus(), o.getLocalSent(), o.getRemoteAck(), o.getReconciled(),
                o.getErrorMsg(), o.getOccurredAt(), o.getReconciledAt())).toList();
    }

    // ==================== #8 失败重发 ====================

    /**
     * 仅 FAILED 可重发（否则 409 中文）；重发即真实外呼同 #6 外呼段；
     * 幂等 transaction_id 复用原 txn_no 更新既有 call_log 行；审计 OUTBOX_RETRY。
     */
    @Transactional
    public OutboxView retry(Long outboxId, String actor) {
        IntegrationOutbox msg = outboxRepo.findById(outboxId)
                .orElseThrow(() -> new NotFound("出站消息不存在"));
        if (!IntegrationOutbox.STATUS_FAILED.equals(msg.getStatus())) {
            throw new Conflict("仅 FAILED 状态的出站消息可重发，当前状态: " + msg.getStatus());
        }
        IntegrationConnector connector = connectorRepo.findById(msg.getConnectorId())
                .orElseThrow(() -> new NotFound("连接器不存在"));

        PushOutcome outcome = push(connector, msg);
        audit.record("INTEGRATION", connector.getCode(), actor, "OUTBOX_RETRY",
                "{\"code\":\"" + connector.getCode() + "\",\"outboxNo\":\"" + msg.getOutboxNo()
                        + "\",\"ok\":" + outcome.ok() + ",\"statusCode\":" + outcome.statusCode() + "}");
        log.info("Outbox 重发 outboxNo={} ok={} statusCode={}", msg.getOutboxNo(), outcome.ok(), outcome.statusCode());
        Map<Long, String> names = new HashMap<>();
        return new OutboxView(msg.getId(), msg.getOutboxNo(), msg.getConnectorId(),
                connectorName(names, msg.getConnectorId()), msg.getBizType(), msg.getTxnNo(), msg.getAmount(),
                msg.getStatus(), msg.getLocalSent(), msg.getRemoteAck(), msg.getReconciled(),
                msg.getErrorMsg(), msg.getOccurredAt(), msg.getReconciledAt());
    }

    // ==================== #9 T+1 对账（本地口径） ====================

    /**
     * 手动 T+1：对 biz_date（默认昨日北京日）本地统计落批次——
     * matched=remote_ack=true 置 MATCHED＋reconciled=true＋reconciled_at；
     * pending/failed 计数；long/short/diff 恒 0 如实（无三方账单源）；
     * uk(connector_id, biz_date) 幂等重放返既有（alreadyExisted=true）；审计 RECONCILE_RUN。
     */
    @Transactional
    public BatchView reconcile(Long connectorId, LocalDate bizDate, String actor) {
        LocalDate date = bizDate != null ? bizDate : LocalDate.now(ZONE).minusDays(1);
        long scope = connectorId != null ? connectorId : IntegrationReconcileBatch.SCOPE_ALL;
        if (scope != IntegrationReconcileBatch.SCOPE_ALL && !connectorRepo.existsById(scope)) {
            throw new NotFound("连接器不存在");
        }

        var existed = batchRepo.findByConnectorIdAndBizDate(scope, date);
        if (existed.isPresent()) {
            return toBatchView(existed.get(), true);
        }

        OffsetDateTime dayStart = date.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime dayEnd = date.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        List<IntegrationOutbox> rows = scope == IntegrationReconcileBatch.SCOPE_ALL
                ? outboxRepo.findByOccurredAtGreaterThanEqualAndOccurredAtLessThan(dayStart, dayEnd)
                : outboxRepo.findByConnectorIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(scope, dayStart, dayEnd);

        OffsetDateTime now = OffsetDateTime.now();
        int matched = 0;
        int pending = 0;
        int failed = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (IntegrationOutbox o : rows) {
            totalAmount = totalAmount.add(o.getAmount() == null ? BigDecimal.ZERO : o.getAmount());
            if (Boolean.TRUE.equals(o.getRemoteAck())) {
                if (!Boolean.TRUE.equals(o.getReconciled())) {
                    o.setStatus(IntegrationOutbox.STATUS_MATCHED);
                    o.setReconciled(true);
                    o.setReconciledAt(now);
                    outboxRepo.save(o);
                }
                matched++;
            } else if (IntegrationOutbox.STATUS_FAILED.equals(o.getStatus())) {
                failed++;
            } else {
                pending++;
            }
        }

        IntegrationReconcileBatch batch = new IntegrationReconcileBatch();
        batch.setBatchNo(nextBatchNo());
        batch.setConnectorId(scope);
        batch.setBizDate(date);
        batch.setTotalCount(rows.size());
        batch.setMatchedCount(matched);
        batch.setPendingCount(pending);
        batch.setLongCount(0);
        batch.setShortCount(0);
        batch.setFailedCount(failed);
        batch.setTotalAmount(totalAmount);
        batch.setDiffAmount(BigDecimal.ZERO);
        batch.setStatus(IntegrationReconcileBatch.STATUS_DONE);
        batch.setStartedAt(now);
        batch.setFinishedAt(now);
        IntegrationReconcileBatch saved = batchRepo.save(batch);

        String scopeLabel = scope == IntegrationReconcileBatch.SCOPE_ALL ? "ALL" : String.valueOf(scope);
        audit.record("INTEGRATION", saved.getBatchNo(), actor, "RECONCILE_RUN",
                "{\"batchNo\":\"" + saved.getBatchNo() + "\",\"bizDate\":\"" + date
                        + "\",\"connectorId\":" + scope + ",\"total\":" + rows.size()
                        + ",\"matched\":" + matched + ",\"pending\":" + pending
                        + ",\"failed\":" + failed + "}");
        log.info("T+1 对账批次 batchNo={} scope={} bizDate={} total={} matched={} pending={} failed={}",
                saved.getBatchNo(), scopeLabel, date, rows.size(), matched, pending, failed);
        return toBatchView(saved, false);
    }

    // ==================== #10 批次列表 ====================

    /** started_at 倒序；limit 默认 100 上限 500。 */
    @Transactional(readOnly = true)
    public List<BatchView> listBatches(Integer limit) {
        int size = limit == null || limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
        return batchRepo.findAllByOrderByStartedAtDesc(PageRequest.of(0, size)).stream()
                .map(b -> toBatchView(b, false)).toList();
    }

    // ==================== 外呼段（#6/#8 共用真实外呼） ====================

    /**
     * 真实外呼：POST connector.endpoint（body=镜像 JSON，连接 3s/读取 3s）——
     * 2xx→ACK/remote_ack=true＋error_msg 清；其余（4xx/5xx/网络异常）→FAILED＋error_msg 如实。
     * 逐条落 call_log：transaction_id=txn_no 复用（首次落行，retry 更新既有行，uk 幂等锚）。
     */
    private PushOutcome push(IntegrationConnector connector, IntegrationOutbox msg) {
        String body = "{\"outboxNo\":\"" + msg.getOutboxNo() + "\",\"txnNo\":\"" + msg.getTxnNo()
                + "\",\"bizType\":\"" + msg.getBizType() + "\",\"amount\":"
                + (msg.getAmount() == null ? "null" : "\"" + msg.getAmount().toPlainString() + "\"")
                + ",\"occurredAt\":\"" + msg.getOccurredAt() + "\",\"source\":\"meiyun-txn\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        long start = System.currentTimeMillis();
        int statusCode = 0;
        boolean ok;
        String errorMsg = null;
        try {
            var response = pushTemplate.postForEntity(connector.getEndpoint(),
                    new HttpEntity<>(body, headers), String.class);
            statusCode = response.getStatusCode().value();
            ok = true;
        } catch (HttpClientErrorException ex) {
            statusCode = ex.getStatusCode().value();
            ok = false;
            errorMsg = "三方拒绝(HTTP " + statusCode + ")";
        } catch (HttpServerErrorException ex) {
            statusCode = ex.getStatusCode().value();
            ok = false;
            errorMsg = "三方服务异常(HTTP " + statusCode + ")";
        } catch (Exception ex) {
            ok = false;
            errorMsg = "网关不可达/超时：" + ex.getMessage();
        }
        int latency = (int) (System.currentTimeMillis() - start);

        OffsetDateTime now = OffsetDateTime.now();
        if (ok) {
            msg.setStatus(IntegrationOutbox.STATUS_ACK);
            msg.setRemoteAck(true);
            msg.setErrorMsg(null);
        } else {
            msg.setStatus(IntegrationOutbox.STATUS_FAILED);
            msg.setRemoteAck(false);
            msg.setErrorMsg(truncate(errorMsg, 250));
        }
        outboxRepo.save(msg);

        IntegrationCallLog entry = callLogRepo
                .findByConnectorIdAndTransactionId(connector.getId(), msg.getTxnNo())
                .orElseGet(() -> {
                    IntegrationCallLog created = new IntegrationCallLog();
                    created.setConnectorId(connector.getId());
                    created.setTransactionId(msg.getTxnNo());
                    created.setDirection(IntegrationCallLog.DIRECTION_OUT);
                    return created;
                });
        entry.setMethod("POST");
        entry.setEndpoint(connector.getEndpoint());
        entry.setStatusCode(statusCode);
        entry.setLatencyMs(latency);
        entry.setStatus(ok ? IntegrationCallLog.STATUS_ACK : IntegrationCallLog.STATUS_FAIL);
        entry.setErrorMsg(ok ? null : truncate(errorMsg, 250));
        entry.setRequestAt(now);
        callLogRepo.save(entry);

        return new PushOutcome(ok, statusCode, errorMsg);
    }

    // ==================== 单号生成（库内 maxSeqOfDay 照 B95 D7 / ContractRepository 先例） ====================

    private synchronized String nextOutboxNo() {
        String day = LocalDate.now(ZONE).format(DateTimeFormatter.BASIC_ISO_DATE);
        long seq = outboxRepo.maxSeqOfDay("OB" + day + "-%") + 1;
        return "OB" + day + "-" + String.format("%06d", seq);
    }

    private synchronized String nextBatchNo() {
        String day = LocalDate.now(ZONE).format(DateTimeFormatter.BASIC_ISO_DATE);
        long seq = batchRepo.maxSeqOfDay("REC" + day + "-%") + 1;
        return "REC" + day + "-" + String.format("%06d", seq);
    }

    // ==================== 视图与工具 ====================

    private String connectorName(Map<Long, String> names, Long connectorId) {
        if (connectorId == IntegrationReconcileBatch.SCOPE_ALL) {
            return "全部连接器";
        }
        return names.computeIfAbsent(connectorId,
                cid -> connectorRepo.findById(cid).map(IntegrationConnector::getName).orElse("未知连接器"));
    }

    private BatchView toBatchView(IntegrationReconcileBatch b, boolean alreadyExisted) {
        Map<Long, String> names = new HashMap<>();
        return new BatchView(b.getId(), b.getBatchNo(), b.getConnectorId(),
                connectorName(names, b.getConnectorId()), b.getBizDate(),
                b.getTotalCount(), b.getMatchedCount(), b.getPendingCount(),
                b.getLongCount(), b.getShortCount(), b.getFailedCount(),
                b.getTotalAmount(), b.getDiffAmount(), b.getStatus(),
                b.getStartedAt(), b.getFinishedAt(), alreadyExisted);
    }

    private static String truncate(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }

    // ==================== 读模型/异常 ====================

    public record SyncResult(int pulled, int created, int skipped, int ack, int failed,
                             int truncated, String message) {
    }

    public record OutboxView(Long id, String outboxNo, Long connectorId, String connectorName,
                             String bizType, String txnNo, BigDecimal amount, String status,
                             Boolean localSent, Boolean remoteAck, Boolean reconciled,
                             String errorMsg, OffsetDateTime occurredAt, OffsetDateTime reconciledAt) {
    }

    public record BatchView(Long id, String batchNo, Long connectorId, String connectorName,
                            LocalDate bizDate, Integer totalCount, Integer matchedCount,
                            Integer pendingCount, Integer longCount, Integer shortCount,
                            Integer failedCount, BigDecimal totalAmount, BigDecimal diffAmount,
                            String status, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                            boolean alreadyExisted) {
    }

    private record PushOutcome(boolean ok, int statusCode, String errorMsg) {
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
}
