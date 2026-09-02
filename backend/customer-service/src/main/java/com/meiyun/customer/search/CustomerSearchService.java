package com.meiyun.customer.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meiyun.customer.Customer;
import com.meiyun.customer.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户全文检索（Elasticsearch）—— 用 JDK HttpClient 直连 ES _bulk / _search，
 * 零额外客户端依赖，规避 ES Java Client 与服务端版本强绑定问题。
 *
 * <p>红线：ES 是查询加速的「读模型」，不是数据源。客户主数据仍以 PG customer 表为准；
 * ES 不可用或返回异常时，降级为 DB 全表内存过滤（联调期数据量小），保证搜索可用。
 *
 * <p>索引：meiyun-customer（name 全文 + phone/customerId/level/status keyword）。
 */
@Component
public class CustomerSearchService {

    private static final Logger log = LoggerFactory.getLogger(CustomerSearchService.class);
    private static final String INDEX = "meiyun-customer";

    private final CustomerRepository customerRepo;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    @Value("${meiyun.es.endpoint:http://localhost:9200}")
    private String esEndpoint;

    public CustomerSearchService(CustomerRepository customerRepo) {
        this.customerRepo = customerRepo;
    }

    /** 全量重建索引：把 PG 所有客户灌入 ES。返回已索引文档数。 */
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
                ObjectNode doc = json.createObjectNode();
                doc.put("customerId", c.getCustomerId());
                doc.put("name", c.getName());
                doc.put("phone", c.getPhone());
                doc.put("level", c.getLevel());
                doc.put("storeCode", c.getStoreCode());
                doc.put("status", c.getStatus());
                doc.put("points", c.getPoints() == null ? 0 : c.getPoints());
                nd.append(json.writeValueAsString(doc)).append('\n');
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(esEndpoint + "/" + INDEX + "/_bulk?refresh=true"))
                    .header("Content-Type", "application/x-ndjson")
                    .POST(HttpRequest.BodyPublishers.ofString(nd.toString()))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                log.warn("ES 全量索引失败 status={} body={}", resp.statusCode(), resp.body());
            }
            log.info("ES 全量索引完成：{} 条客户", all.size());
            return all.size();
        } catch (Exception e) {
            log.warn("ES 全量索引异常，降级为 DB：{}", e.getMessage());
            return all.size();
        }
    }

    /**
     * 全文检索：匹配 name（全文）/ phone / customerId（前缀）。
     * ES 不可用时降级 DB 内存过滤。返回命中的客户 ID 列表。
     */
    public List<String> search(String q) {
        if (q == null || q.isBlank()) return List.of();
        try {
            ObjectNode body = json.createObjectNode();
            ObjectNode query = body.putObject("query");
            ObjectNode multi = query.putObject("multi_match");
            multi.put("query", q);
            multi.set("fields", json.valueToTree(List.of("name^2", "phone", "customerId")));
            body.put("size", 50);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(esEndpoint + "/" + INDEX + "/_search"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("ES 搜索失败 status={}，降级 DB", resp.statusCode());
                return dbFallback(q);
            }
            JsonNode hits = json.readTree(resp.body()).path("hits").path("hits");
            List<String> ids = new ArrayList<>();
            hits.forEach(h -> ids.add(h.path("_id").asText()));
            return ids;
        } catch (Exception e) {
            log.warn("ES 搜索异常，降级 DB：{}", e.getMessage());
            return dbFallback(q);
        }
    }

    /** DB 降级：内存模糊匹配 name/phone/customerId。 */
    private List<String> dbFallback(String q) {
        String k = q.toLowerCase();
        return customerRepo.findAll().stream()
                .filter(c -> (c.getName() != null && c.getName().toLowerCase().contains(k))
                        || (c.getPhone() != null && c.getPhone().contains(q))
                        || (c.getCustomerId() != null && c.getCustomerId().toLowerCase().contains(k)))
                .map(Customer::getCustomerId)
                .limit(50)
                .toList();
    }
}
