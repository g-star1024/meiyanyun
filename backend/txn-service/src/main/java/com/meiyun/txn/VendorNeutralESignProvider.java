package com.meiyun.txn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 棒⑧卡3 电子签接入位·厂商无关默认实现（配置即用）：
 * 配置目录 ESIGN_DIRECT（SWITCH + configJson 厂商参数模板 provider/endpoint/appId/callbackPath）
 * + ESIGN_SECRET（AES-GCM 密文）驱动，通用 REST 契约 POST {endpoint} 建签署流程，
 * provider 字段仅作标识不绑定具体厂商 SDK——厂商未采购前接入位先行立起，采购到位配置即用。
 *
 * <p><b>诚实降级（铁律 11）：</b>开关未启用 / endpoint·appId 空缺 / 密钥未配置一律 SKIPPED
 * 如实中文原因，绝不伪造已发送、不静默回落线下；厂商侧 4xx/5xx/网络异常 → 502 如实上抛，
 * 不落「半发送」脏状态。
 *
 * <p>具体厂商（e签宝/法大大等）采购到位后：以本接口新实现类插拔（@Primary），调用契约不变。
 */
@Component
public class VendorNeutralESignProvider implements ESignProvider {

    private static final Logger log = LoggerFactory.getLogger(VendorNeutralESignProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IntegrationConfigClient integrationConfig;
    private final RestTemplate restTemplate;

    /** env 兜底三件套：配置窗口启用值优先，本组仅在配置窗未启用时生效。 */
    @Value("${meiyun.esign.direct-enabled:false}")
    private boolean envEnabled;
    @Value("${meiyun.esign.direct-config:}")
    private String envConfig;
    @Value("${meiyun.esign.secret:}")
    private String envSecret;

    public VendorNeutralESignProvider(IntegrationConfigClient integrationConfig, RestTemplate restTemplate) {
        this.integrationConfig = integrationConfig;
        this.restTemplate = restTemplate;
    }

    @Override
    public boolean enabled() {
        return integrationConfig.resolveSwitch("ESIGN_DIRECT", envEnabled);
    }

    @Override
    public ESignSendResult send(Contract contract) {
        if (!enabled()) {
            return ESignSendResult.skipped("电子签接入位未启用（配置目录 ESIGN_DIRECT 关闭），合同维持线下签署流程");
        }
        Map<String, String> cfg = parseConfig();
        String endpoint = cfg.get("endpoint");
        String appId = cfg.get("appId");
        if (endpoint == null || endpoint.isBlank() || appId == null || appId.isBlank()) {
            return ESignSendResult.skipped("电子签厂商参数未配齐（endpoint/appId 空缺），请在集成配置目录补齐 ESIGN_DIRECT 扩展参数");
        }
        String secret = integrationConfig.resolveSecret("ESIGN_SECRET", envSecret);
        if (secret == null || secret.isBlank()) {
            return ESignSendResult.skipped("电子签厂商密钥未配置（ESIGN_SECRET），发起签署按诚实降级不发送");
        }
        String callbackPath = cfg.getOrDefault("callbackPath", "/api/txn/esign/callback");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appId", appId);
        body.put("contractNo", contract.getContractNo());
        body.put("title", contract.getTitle());
        body.put("signDate", String.valueOf(contract.getSignDate()));
        body.put("callbackPath", callbackPath);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(secret);
        try {
            ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                    endpoint, new HttpEntity<>(body, headers), JsonNode.class);
            JsonNode json = resp.getBody();
            String flowId = (json != null && json.hasNonNull("flowId")) ? json.get("flowId").asText() : null;
            if (!resp.getStatusCode().is2xxSuccessful() || flowId == null || flowId.isBlank()) {
                log.warn("电子签厂商建签署流程响应异常：status={} body={}", resp.getStatusCode(), json);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "电子签厂商响应缺少签署流程号（flowId），请核查厂商接入配置");
            }
            return ESignSendResult.sent(flowId);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "电子签厂商调用失败：" + e.getMessage());
        }
    }

    /** configJson 库启用值优先、env 兜底；逐字段判空由调用方负责（SKIPPED 诚实降级）。 */
    private Map<String, String> parseConfig() {
        String raw = integrationConfig.resolveConfigJson("ESIGN_DIRECT");
        if (raw == null || raw.isBlank()) {
            raw = envConfig;
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        try {
            JsonNode node = JSON.readTree(raw);
            node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText("")));
        } catch (Exception e) {
            log.warn("ESIGN_DIRECT configJson 解析失败（按空配置降级）：{}", e.getMessage());
        }
        return out;
    }
}
