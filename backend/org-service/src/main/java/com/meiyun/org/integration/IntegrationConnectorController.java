package com.meiyun.org.integration;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/**
 * T3 数据中台·三方连接器管理面（真人，/api/org/integration 前缀，网关正常鉴权）：
 * 连接器目录（integration:view，类级兜底）＋新建（integration:create）＋
 * 编辑/测试连接（integration:edit）＋调用日志（integration:view）。
 * T3-B2 追加：单向镜像同步/失败重发（integration:sync）＋Outbox 列表/对账批次（view）＋
 * 手动 T+1 对账（integration:reconcile）。
 * 与 B57 配置窗口 /api/org/integrations（复数）不同路径，零冲突。
 */
@RestController
@RequestMapping("/api/org/integration")
@RequirePerm("integration:view")
public class IntegrationConnectorController {

    private final IntegrationConnectorService service;
    private final IntegrationOutboxService outboxService;

    public IntegrationConnectorController(IntegrationConnectorService service,
                                          IntegrationOutboxService outboxService) {
        this.service = service;
        this.outboxService = outboxService;
    }

    /** #1 连接器目录：七类全量＋24h 真实聚合计数（calls/errors）。 */
    @GetMapping("/connectors")
    public List<IntegrationConnectorService.ConnectorView> listConnectors() {
        return service.listConnectors();
    }

    /** #2 新建连接器：code 重码 409；endpoint 非 https 未二次确认 422；初始 DISCONNECTED 诚实态。 */
    @PostMapping("/connectors")
    @RequirePerm("integration:create")
    public IntegrationConnectorService.ConnectorView create(
            @RequestBody IntegrationConnectorService.CreateRequest req) {
        try {
            return service.create(req, DataScope.currentActor());
        } catch (IntegrationConnectorService.InsecureEndpoint e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (IntegrationConnectorService.Conflict e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** #3 编辑连接器：name/endpoint/credentialKey 局部更新；审计 CONNECTOR_UPDATE。 */
    @PutMapping("/connectors/{id}")
    @RequirePerm("integration:edit")
    public IntegrationConnectorService.ConnectorView update(
            @PathVariable Long id,
            @RequestBody IntegrationConnectorService.UpdateRequest req) {
        try {
            return service.update(id, req, DataScope.currentActor());
        } catch (IntegrationConnectorService.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IntegrationConnectorService.InsecureEndpoint e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * #4 测试连接（真实探测，连接 3s/读取 3s）：
     * 2xx/3xx/4xx 可达 → CONNECTED；5xx/网络异常 → ERROR＋last_error 如实；
     * 逐次落 call_log（transaction_id=TEST-{id}-{ts}）＋审计 CONNECTOR_TEST。
     */
    @PostMapping("/connectors/{id}/test")
    @RequirePerm("integration:edit")
    public IntegrationConnectorService.TestConnectorResult test(@PathVariable Long id) {
        try {
            return service.test(id, DataScope.currentActor());
        } catch (IntegrationConnectorService.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** #5 调用日志：request_at 倒序；connectorId 可选过滤；limit 默认 100 上限 500。 */
    @GetMapping("/call-logs")
    public List<IntegrationConnectorService.CallLogView> listLogs(
            @RequestParam(required = false) Long connectorId,
            @RequestParam(required = false) Integer limit) {
        return service.listLogs(connectorId, limit);
    }

    /**
     * #6 单向镜像同步：txn 拉已支付单（北京昨日~今日）→ uk 幂等落 outbox →
     * 逐条真实外呼（2xx→ACK，其余 FAILED 如实）→ 审计 OUTBOX_SYNC。
     * 拉取失败 502 如实（绝不软降级空列表伪造同步 0 条）。
     */
    @PostMapping("/connectors/{id}/sync")
    @RequirePerm("integration:sync")
    public IntegrationOutboxService.SyncResult sync(@PathVariable Long id) {
        try {
            return outboxService.sync(id, DataScope.currentActor());
        } catch (IntegrationOutboxService.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (TxnPaidOrderClient.PullFailed e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    /** #7 Outbox 列表：occurred_at 倒序；status/connectorId 可选过滤；limit 默认 100 上限 500。 */
    @GetMapping("/outbox")
    public List<IntegrationOutboxService.OutboxView> listOutbox(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long connectorId,
            @RequestParam(required = false) Integer limit) {
        return outboxService.listOutbox(status, connectorId, limit);
    }

    /**
     * #8 失败重发：仅 FAILED 可重发（否则 409 中文）；真实外呼同 #6 外呼段；
     * 幂等 transaction_id 复用原 txn_no 更新既有 call_log 行；审计 OUTBOX_RETRY。
     */
    @PostMapping("/outbox/{id}/retry")
    @RequirePerm("integration:sync")
    public IntegrationOutboxService.OutboxView retry(@PathVariable Long id) {
        try {
            return outboxService.retry(id, DataScope.currentActor());
        } catch (IntegrationOutboxService.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IntegrationOutboxService.Conflict e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * #9 手动 T+1 对账（本地口径）：biz_date 默认昨日北京日；connectorId 缺省=全部（scope 0）；
     * matched=remote_ack=true 置 MATCHED；long/short/diff 恒 0 如实；
     * uk(connector_id, biz_date) 幂等重放返既有批次（alreadyExisted=true）；审计 RECONCILE_RUN。
     */
    @PostMapping("/reconcile")
    @RequirePerm("integration:reconcile")
    public IntegrationOutboxService.BatchView reconcile(
            @RequestParam(required = false) Long connectorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        try {
            return outboxService.reconcile(connectorId, bizDate, DataScope.currentActor());
        } catch (IntegrationOutboxService.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** #10 对账批次列表：started_at 倒序；limit 默认 100 上限 500。 */
    @GetMapping("/reconcile-batches")
    public List<IntegrationOutboxService.BatchView> listBatches(
            @RequestParam(required = false) Integer limit) {
        return outboxService.listBatches(limit);
    }
}
