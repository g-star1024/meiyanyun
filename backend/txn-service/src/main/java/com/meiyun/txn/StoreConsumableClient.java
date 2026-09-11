package com.meiyun.txn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * txn → store 服务间客户端（B5 成本库存）：耗材领用/报损终审通过后，交易域不直写库存域表，
 * 一律经 store 内部端点以系统身份（X-Internal-Token，perms=["*"]）回调扣库，拆库后零改动。
 *
 * <p>错误口径（与 CustomerCardClient 一致）：store 返回 4xx（404 SKU 未建档 / 400 参数 /
 * 422 库存不足）→ 透传其状态码与中文 message（终审事务回滚，待办不置 APPROVED、成本事件不入 outbox）；
 * 网络异常 / 5xx → 502 中文（同样回滚，杜绝「审批办结但库存未扣」）。
 * store 侧以 bizRef（审批待办号）+ SKU 为幂等键，本客户端失败重试安全（不双扣，重放回返原行金额）。
 */
@Component
public class StoreConsumableClient {

    private static final Logger log = LoggerFactory.getLogger(StoreConsumableClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;

    @Value("${store.service.url:http://127.0.0.1:8085}")
    private String storeBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public StoreConsumableClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 终审回调扣库：POST /api/stores/internal/consumables/deduct。
     *
     * @param bizRef    审批待办号（AP...，store 侧幂等键）
     * @param storeCode 门店码
     * @param moveType  USE（领用）/ SCRAP（报损）
     * @param operator  终审操作人（留痕）
     * @param lines     出库明细（skuCode/qty/remark）
     * @return 扣库结果（totalAmountFen 成本合计分 + 逐行定格金额），供成本事件入账
     */
    @SuppressWarnings("unchecked")
    public DeductResult deduct(String bizRef, String storeCode, String moveType, String operator,
                               List<DeductLine> lines) {
        List<Map<String, Object>> lineMaps = new ArrayList<>();
        for (DeductLine l : lines) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("skuCode", l.skuCode());
            m.put("qty", l.qty());
            m.put("remark", l.remark());
            lineMaps.add(m);
        }
        Map<String, Object> body = Map.of(
                "bizRef", nz(bizRef),
                "storeCode", nz(storeCode),
                "moveType", nz(moveType),
                "operator", nz(operator),
                "lines", lineMaps);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            Map<String, Object> resp = restTemplate.postForEntity(
                    storeBaseUrl + "/api/stores/internal/consumables/deduct",
                    new HttpEntity<>(body, headers), Map.class).getBody();
            long total = 0L;
            List<DeductLineResult> resultLines = new ArrayList<>();
            if (resp != null) {
                Object t = resp.get("totalAmountFen");
                if (t instanceof Number n) total = n.longValue();
                Object rawLines = resp.get("lines");
                if (rawLines instanceof List<?> ls) {
                    for (Object o : ls) {
                        if (o instanceof Map<?, ?> lm) {
                            resultLines.add(new DeductLineResult(
                                    str(lm.get("skuCode")), str(lm.get("name")),
                                    lm.get("qty") instanceof Number q ? q.intValue() : 0,
                                    lm.get("unitCostFen") instanceof Number u ? u.longValue() : 0L,
                                    lm.get("amountFen") instanceof Number a ? a.longValue() : 0L));
                        }
                    }
                }
            }
            return new DeductResult(total, resultLines);
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            String msg = extractMessage(e.getResponseBodyAsString());
            if (status >= 400 && status < 500) {
                // 业务拒绝（库存不足 422 / SKU 未建档 404 / 参数 400）：原样透传中文，调用方据此中止终审
                log.info("耗材扣库被 store 拒绝 status={} bizRef={} msg={}", status, bizRef, msg);
                throw new ResponseStatusException(HttpStatus.valueOf(status), msg);
            }
            log.error("耗材扣库 store 服务端错误 status={} bizRef={} body={}", status, bizRef, e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "耗材出库失败：库存服务暂不可用，请稍后重试（本笔审批已回滚，库存未扣、成本未记账）");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("耗材扣库 store 调用异常 bizRef={}: {}", bizRef, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "耗材出库失败：无法连接库存服务，请稍后重试（本笔审批已回滚，库存未扣、成本未记账）");
        }
    }

    /**
     * BOM 自动扣料（B10）：POST /api/stores/internal/consumables/bom-deduct。
     * 由 store 域按 projectName 解析配方（门店行 &gt; 集团模板）并出库，txn 不传明细。
     *
     * <p>与终审扣库 {@link #deduct} 的错误口径不同：本方法调用发生在划扣事务<b>提交后</b>，
     * 4xx（库存不足 422 / SKU 未建档 404）/ 5xx / 网络异常一律抛 ResponseStatusException（中文），
     * 由 {@link BomDeductService} 捕获登记 bom_deduct_exception，<b>绝不向上抛出阻断划扣</b>。
     * store 返回 skipped=true 表示该项目未配 BOM（静默跳过，非异常）。
     * 幂等：bizRef=BOM:{writeoffId}，重试安全（store 侧不双扣、回返原行定格金额）。
     */
    public BomDeductResult deductByProject(String bizRef, String storeCode,
                                           String projectName, String operator) {
        Map<String, Object> body = Map.of(
                "bizRef", nz(bizRef),
                "storeCode", nz(storeCode),
                "projectName", nz(projectName),
                "operator", nz(operator));
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            Map<String, Object> resp = restTemplate.postForEntity(
                    storeBaseUrl + "/api/stores/internal/consumables/bom-deduct",
                    new HttpEntity<>(body, headers), Map.class).getBody();
            long total = 0L;
            boolean skipped = false;
            List<DeductLineResult> resultLines = new ArrayList<>();
            if (resp != null) {
                Object t = resp.get("totalAmountFen");
                if (t instanceof Number n) total = n.longValue();
                Object sk = resp.get("skipped");
                if (sk instanceof Boolean b) skipped = b;
                Object rawLines = resp.get("lines");
                if (rawLines instanceof List<?> ls) {
                    for (Object o : ls) {
                        if (o instanceof Map<?, ?> lm) {
                            resultLines.add(new DeductLineResult(
                                    str(lm.get("skuCode")), str(lm.get("name")),
                                    lm.get("qty") instanceof Number q ? q.intValue() : 0,
                                    lm.get("unitCostFen") instanceof Number u ? u.longValue() : 0L,
                                    lm.get("amountFen") instanceof Number a ? a.longValue() : 0L));
                        }
                    }
                }
            }
            return new BomDeductResult(skipped, total, resultLines);
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            String rawBody = e.getResponseBodyAsString();
            String msg = extractMessage(rawBody);
            if (status >= 400 && status < 500) {
                // 业务拒绝（库存不足 / SKU 未建档）：中文透传，调用方登记异常单
                log.info("BOM 自动扣料被 store 拒绝 status={} bizRef={} msg={}", status, bizRef, msg);
                throw new BomShortageException(HttpStatus.valueOf(status), msg, extractShortages(rawBody));
            }
            log.error("BOM 自动扣料 store 服务端错误 status={} bizRef={} body={}", status, bizRef, rawBody);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "BOM 自动扣料失败：库存服务暂不可用，划扣已完成，请在扣料异常清单重试");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("BOM 自动扣料 store 调用异常 bizRef={}: {}", bizRef, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "BOM 自动扣料失败：无法连接库存服务，划扣已完成，请在扣料异常清单重试");
        }
    }

    /** 从 store 错误体 {"message":"中文"} 提取中文原因；解析失败回落通用文案。 */
    private static String extractMessage(String body) {
        if (body != null && body.contains("\"message\"")) {
            try {
                String m = MAPPER.readTree(body).path("message").asText(null);
                if (m != null && !m.isBlank()) return m;
            } catch (Exception ignored) {
                // 落到兜底文案
            }
        }
        return "库存服务拒绝了本次出库，请核对耗材档案与库存后重试";
    }

    /**
     * 从 BOM 内部端点 422 错误体提取结构化缺料明细（B34）：
     * {@code {"message":"...","shortages":[{skuCode,skuName,needQty,stockQty,unit}]}} → 紧凑 JSON 字符串。
     *
     * <p>返回 null 表示本次失败无行明细（如 404 SKU 未建档、旧版 store 无该字段），
     * 调用方据此保持 {@code detail_json} 为空，与既有行为一致。
     */
    private static String extractShortages(String body) {
        if (body == null || !body.contains("\"shortages\"")) return null;
        try {
            var node = MAPPER.readTree(body).path("shortages");
            if (!node.isArray() || node.isEmpty()) return null;
            String json = MAPPER.writeValueAsString(node);
            // 与 bom_deduct_exception.detail_json 列宽（2048）对齐，超长丢弃而非截断出非法 JSON
            return json.length() > 2048 ? null : json;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 出库明细行（与 store 侧 DeductLine 契约对齐）。 */
    public record DeductLine(String skuCode, int qty, String remark) {
    }

    /** 扣库结果行（金额分，定格移动平均成本）。 */
    public record DeductLineResult(String skuCode, String name, int qty, long unitCostFen, long amountFen) {
    }

    /** 扣库结果：成本合计（分）+ 逐行明细。 */
    public record DeductResult(long totalAmountFen, List<DeductLineResult> lines) {
    }

    /** BOM 按项目扣料结果：skipped=true 表示未配 BOM 静默跳过；totalAmountFen 为成本合计（分）。 */
    public record BomDeductResult(boolean skipped, long totalAmountFen, List<DeductLineResult> lines) {
    }
}
