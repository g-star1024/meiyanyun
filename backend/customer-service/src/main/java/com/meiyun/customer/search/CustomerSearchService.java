package com.meiyun.customer.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meiyun.customer.Customer;
import com.meiyun.customer.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户全文检索（Elasticsearch）—— 用 JDK HttpClient 直连 ES，
 * 零额外客户端依赖，规避 ES Java Client 与服务端版本强绑定问题。
 *
 * <p>红线：ES 是查询加速的「读模型」，不是数据源。客户主数据仍以 PG customer 表为准：
 * <ul>
 *   <li>写：建档事务只落 outbox，{@code CustomerSearchEventRelayJob} 回查 PG 后调
 *       {@link #upsert(Customer)} 幂等写入（_id=customerId），ES 故障只重试不阻断建档；</li>
 *   <li>读：{@link #search(String)} 命中 ES 后合并近期 PG 客户（兜中继延迟，建档即可搜到）；
 *       ES 连续失败时熔断开路，直接降级 DB 全表内存过滤（联调期数据量小）。</li>
 * </ul>
 *
 * <p>索引：meiyun-customer，启动时幂等确保显式 mapping（name 分词 + keyword 子字段，
 * phone/customerId/level/status/storeCode 精确 keyword，points long）；索引缺失才创建并全量回填，
 * 已存在的索引不强制改 mapping（避免与存量动态 mapping 冲突）。
 */
@Component
public class CustomerSearchService {

    private static final Logger log = LoggerFactory.getLogger(CustomerSearchService.class);
    private static final String INDEX = "meiyun-customer";

    /** 熔断：ES 连续失败达到阈值后开路一段时间，期间不打 ES，直接 DB 降级。 */
    private static final int FAILURE_THRESHOLD = 3;
    private static final long OPEN_MILLIS = 30_000L;
    /** 读时合并：最近建档的 N 个 PG 客户参与内存匹配，覆盖 outbox 中继的秒级延迟。 */
    private static final int RECENT_MERGE_LIMIT = 200;
    private static final int SEARCH_LIMIT = 50;

    private final CustomerRepository customerRepo;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long openUntilMillis = 0L;
    private volatile boolean indexReady = false;

    @Value("${meiyun.es.endpoint:http://localhost:9200}")
    private String esEndpoint;

    public CustomerSearchService(CustomerRepository customerRepo) {
        this.customerRepo = customerRepo;
    }

    /** ES 中继投递结果：SENT 已受理；RETRY 可恢复失败（连接/5xx/429/熔断中）；DEAD 确定性拒绝（4xx）。 */
    public enum UpsertResult { SENT, RETRY, DEAD }

    /** 启动就绪后幂等确保索引与显式 mapping；全新索引（或 ES 曾不可用）首次创建时全量回填。 */
    @EventListener(ApplicationReadyEvent.class)
    public void initOnReady() {
        try {
            if (ensureIndex()) {
                log.info("ES 客户索引就绪：{}", INDEX);
            }
        } catch (Exception e) {
            log.warn("ES 客户索引初始化失败，待中继/搜索时重试：{}", e.getMessage());
        }
    }

    /**
     * 幂等确保索引存在且为显式 mapping。
     *
     * @return true=索引可用（已存在或本次创建成功）；false=ES 不可用，调用方应稍后重试
     */
    public synchronized boolean ensureIndex() {
        if (indexReady) return true;
        try {
            // Java 17 的 Builder 无 HEAD() 便捷方法（18+ 才有），用通用 method 形式
            int head = sendRaw(HttpRequest.newBuilder()
                    .uri(URI.create(esEndpoint + "/" + INDEX))
                    .timeout(Duration.ofSeconds(3))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build()).statusCode();
            if (head == 200) {
                indexReady = true;
                return true;
            }
            if (head != 404) {
                recordFailure();
                log.warn("ES 索引探测异常 status={}，按不可用处理", head);
                return false;
            }
            HttpRequest create = HttpRequest.newBuilder()
                    .uri(URI.create(esEndpoint + "/" + INDEX))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(indexMappingJson()))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = sendRaw(create);
            if (resp.statusCode() >= 300) {
                recordFailure();
                log.warn("ES 索引创建失败 status={} body={}", resp.statusCode(), resp.body());
                return false;
            }
            indexReady = true;
            recordSuccess();
            log.info("ES 客户索引已创建（显式 mapping）：{}", INDEX);
            // 索引首次创建：把存量客户全量回填，保证新环境/ES 重置后搜索可用
            int n = reindexAll();
            log.info("ES 客户索引初始回填完成：{} 条", n);
            return true;
        } catch (Exception e) {
            recordFailure();
            log.warn("ES 索引确保异常，待重试：{}", e.getMessage());
            return false;
        }
    }

    /** 单客户幂等写入（PUT _doc/{customerId}，重复投递覆盖为同一文档，不双算）。中继任务调用。 */
    public UpsertResult upsert(Customer c) {
        if (c == null || c.getCustomerId() == null) return UpsertResult.DEAD;
        if (isCircuitOpen()) return UpsertResult.RETRY;
        if (!ensureIndex()) return UpsertResult.RETRY;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(esEndpoint + "/" + INDEX + "/_doc/" + c.getCustomerId()))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(toDoc(c))))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = sendRaw(req);
            int sc = resp.statusCode();
            if (sc >= 200 && sc < 300) {
                recordSuccess();
                return UpsertResult.SENT;
            }
            if (sc == 429 || sc >= 500) {
                recordFailure();
                log.warn("ES 单条写入可恢复失败 status={} customerId={}", sc, c.getCustomerId());
                return UpsertResult.RETRY;
            }
            // 4xx（非 429，如 mapping 拒绝）：确定性失败，重试无意义 → DEAD 待人工
            log.error("ES 单条写入被拒绝 status={} body={} customerId={}", sc, resp.body(), c.getCustomerId());
            return UpsertResult.DEAD;
        } catch (Exception e) {
            recordFailure();
            log.warn("ES 单条写入异常，待重试 customerId={}: {}", c.getCustomerId(), e.getMessage());
            return UpsertResult.RETRY;
        }
    }

    /** 全量重建索引：把 PG 所有客户灌入 ES。返回已索引文档数（ES 不可用时不抛异常，返回库内条数）。 */
    public int reindexAll() {
        List<Customer> all = customerRepo.findAll();
        if (all.isEmpty()) return 0;
        try {
            StringBuilder nd = new StringBuilder();
            for (Customer c : all) {
                ObjectNode action = json.createObjectNode();
                ObjectNode indexNode = action.putObject("index");
                indexNode.put("_index", INDEX);
                indexNode.put("_id", c.getCustomerId());
                nd.append(json.writeValueAsString(action)).append('\n');
                nd.append(json.writeValueAsString(toDoc(c))).append('\n');
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(esEndpoint + "/" + INDEX + "/_bulk?refresh=true"))
                    .header("Content-Type", "application/x-ndjson")
                    .POST(HttpRequest.BodyPublishers.ofString(nd.toString()))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = sendRaw(req);
            if (resp.statusCode() >= 300) {
                recordFailure();
                log.warn("ES 全量索引失败 status={} body={}", resp.statusCode(), resp.body());
            } else {
                recordSuccess();
            }
            log.info("ES 全量索引完成：{} 条客户", all.size());
            return all.size();
        } catch (Exception e) {
            recordFailure();
            log.warn("ES 全量索引异常，降级为 DB：{}", e.getMessage());
            return all.size();
        }
    }

    /**
     * 全文检索：匹配 name（全文）/ phone / customerId（前缀）。
     * ES 命中后合并近期 PG 客户（兜 outbox 中继延迟）；熔断/异常时降级 DB 内存过滤。
     * 返回命中的客户 ID 列表（数据域过滤由 Controller 回库后统一处理）。
     */
    public List<String> search(String q) {
        if (q == null || q.isBlank()) return List.of();
        if (isCircuitOpen()) return dbFallback(q);
        try {
            ObjectNode body = json.createObjectNode();
            ObjectNode query = body.putObject("query");
            ObjectNode multi = query.putObject("multi_match");
            multi.put("query", q);
            multi.set("fields", json.valueToTree(List.of("name^2", "phone", "customerId")));
            body.put("size", SEARCH_LIMIT);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(esEndpoint + "/" + INDEX + "/_search"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = sendRaw(req);
            if (resp.statusCode() != 200) {
                recordFailure();
                log.warn("ES 搜索失败 status={}，降级 DB", resp.statusCode());
                return dbFallback(q);
            }
            recordSuccess();
            JsonNode hits = json.readTree(resp.body()).path("hits").path("hits");
            Set<String> ids = new LinkedHashSet<>();
            hits.forEach(h -> ids.add(h.path("_id").asText()));
            mergeRecentFromDb(ids, q.trim());
            return ids.stream().limit(SEARCH_LIMIT).toList();
        } catch (Exception e) {
            recordFailure();
            log.warn("ES 搜索异常，降级 DB：{}", e.getMessage());
            return dbFallback(q);
        }
    }

    /** 读时合并：最近建档的 PG 客户若命中关键字且不在 ES 结果中则补入（覆盖中继秒级延迟）。 */
    private void mergeRecentFromDb(Set<String> ids, String q) {
        String k = q.toLowerCase();
        for (Customer c : customerRepo.findTop200ByOrderByCreatedAtDesc()) {
            if (ids.contains(c.getCustomerId())) continue;
            if (matches(c, q, k)) ids.add(c.getCustomerId());
        }
    }

    /** DB 降级：内存模糊匹配 name/phone/customerId。 */
    private List<String> dbFallback(String q) {
        String k = q.trim().toLowerCase();
        List<String> ids = new ArrayList<>();
        for (Customer c : customerRepo.findAll()) {
            if (matches(c, q.trim(), k)) {
                ids.add(c.getCustomerId());
                if (ids.size() >= SEARCH_LIMIT) break;
            }
        }
        return ids;
    }

    private static boolean matches(Customer c, String rawQ, String lowerQ) {
        return (c.getName() != null && c.getName().toLowerCase().contains(lowerQ))
                || (c.getPhone() != null && c.getPhone().contains(rawQ))
                || (c.getCustomerId() != null && c.getCustomerId().toLowerCase().contains(lowerQ));
    }

    private ObjectNode toDoc(Customer c) {
        ObjectNode doc = json.createObjectNode();
        doc.put("customerId", c.getCustomerId());
        doc.put("name", c.getName());
        doc.put("phone", c.getPhone());
        doc.put("level", c.getLevel());
        doc.put("storeCode", c.getStoreCode());
        doc.put("status", c.getStatus());
        doc.put("points", c.getPoints() == null ? 0 : c.getPoints());
        return doc;
    }

    /** 显式 mapping：name 中文按标准分词并保留 keyword 子字段；其余过滤/精确字段为 keyword。 */
    private String indexMappingJson() {
        return """
                {
                  "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
                  "mappings": {
                    "properties": {
                      "customerId": { "type": "keyword" },
                      "name": { "type": "text", "fields": { "keyword": { "type": "keyword", "ignore_above": 32 } } },
                      "phone": { "type": "keyword" },
                      "level": { "type": "keyword" },
                      "storeCode": { "type": "keyword" },
                      "status": { "type": "keyword" },
                      "points": { "type": "long" }
                    }
                  }
                }
                """;
    }

    private HttpResponse<String> sendRaw(HttpRequest req) throws Exception {
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private boolean isCircuitOpen() {
        return System.currentTimeMillis() < openUntilMillis;
    }

    private void recordSuccess() {
        consecutiveFailures.set(0);
        openUntilMillis = 0L;
    }

    private void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD) {
            openUntilMillis = System.currentTimeMillis() + OPEN_MILLIS;
            consecutiveFailures.set(0);
            log.warn("ES 连续失败 {} 次，熔断开路 {} 秒，期间读请求降级 DB、写事件留 PENDING 重试",
                    FAILURE_THRESHOLD, OPEN_MILLIS / 1000);
        }
    }
}
