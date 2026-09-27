package com.meiyun.org.integration;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * T3 数据中台·三方连接器管理面（真人，/api/org/integration 前缀，网关正常鉴权）：
 * 连接器目录（integration:view，类级兜底）＋新建（integration:create）＋
 * 编辑/测试连接（integration:edit）＋调用日志（integration:view）。
 * 与 B57 配置窗口 /api/org/integrations（复数）不同路径，零冲突。
 */
@RestController
@RequestMapping("/api/org/integration")
@RequirePerm("integration:view")
public class IntegrationConnectorController {

    private final IntegrationConnectorService service;

    public IntegrationConnectorController(IntegrationConnectorService service) {
        this.service = service;
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
}
