package com.meiyun.finance;

import com.meiyun.security.AuthInterceptor;
import com.meiyun.security.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * finance-service 读时聚合：跨 txn / customer / store 三域取数，组装财务只读视图。
 *
 * <p><b>资金口径（避免重复计收入）</b>：
 * <ul>
 *   <li>订单「已收款」→ RF-REVENUE / IN / ORDER / CASHIER：收银实收，资金流入；</li>
 *   <li>退款「REFUNDED」→ RF-REFUND / OUT / REFUND / CASHIER：资金流出（收入抵减）；</li>
 *   <li>核销「DONE 且 card_no 非空」（卡扣次/扣额划扣）→ 成对 RF-DEPOSIT/OUT 与 RF-REVENUE/IN，
 *       refType=WRITEOFF / ERP：预收转收入；</li>
 *   <li>订单整单核销（card_no 为空）<b>不计</b>：仅回写订单「已收款→已核销」状态，无新资金动账，
 *       其收入已在订单收款时计入，重复记会双算；</li>
 *   <li>退卡「REFUNDED」→ 依恒等式 balance=refundAmt+fee 落三条：RF-REFUND/OUT（实退 refundAmt，
 *       CASHIER）+ RF-DEPOSIT/OUT（balance 全额冲预收，ERP）+ RF-REVENUE/IN（fee 违约金转收入，ERP，
 *       fee=0 不计）；退卡无原单号，ORIGINAL 渠道保守留空。</li>
 * </ul>
 *
 * <p><b>降级</b>：任一被调方不可用 / 超时，对应来源回落空集合，log.warn 不抛，不阻断财务页只读展示。
 * <b>数据权限</b>：内部端点以 system(GROUP) 全量返回，本服务聚合后按登录人门店域 canReadStore 逐行收敛。
 * 金额：数据源 Long「分」，视图统一 Double「元」（/100.0，两位小数）。
 */
@Component
public class FinanceAggregationService {

    private static final Logger log = LoggerFactory.getLogger(FinanceAggregationService.class);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    /** 疗程卡单次估值（元）——对齐前端 finReports 活规格（timesRemain * 2000）。 */
    private static final double TIMES_UNIT_VALUE = 2000.0;

    private final RestTemplate restTemplate;

    @Value("${txn.service.url:http://127.0.0.1:8083}")
    private String txnBaseUrl;

    @Value("${customer.service.url:http://127.0.0.1:8082}")
    private String customerBaseUrl;

    @Value("${store.service.url:http://127.0.0.1:8085}")
    private String storeBaseUrl;

    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public FinanceAggregationService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // ==================== 台账流水 ====================

    /** 组装台账流水（已按登录人门店域收敛）。from/to 为 yyyy-MM-dd（含两端，UTC 日界）。 */
    public List<FinanceViewDTO.LedgerEntry> ledger(String storeCode, String from, String to) {
        Map<String, Object> bundle = fetchFlows(storeCode, from, to);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = (List<Map<String, Object>>) bundle.getOrDefault("orders", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refunds = (List<Map<String, Object>>) bundle.getOrDefault("refunds", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> writeoffs = (List<Map<String, Object>>) bundle.getOrDefault("writeoffs", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cardCancels = (List<Map<String, Object>>) bundle.getOrDefault("cardCancels", List.of());

        List<Map<String, Object>> all = new ArrayList<>();
        orders.forEach(o -> all.add(Map.of("kind", "ORDER", "row", o)));
        refunds.forEach(r -> all.add(Map.of("kind", "REFUND", "row", r)));
        writeoffs.forEach(w -> all.add(Map.of("kind", "WRITEOFF", "row", w)));
        cardCancels.forEach(c -> all.add(Map.of("kind", "CARD_CANCEL", "row", c)));

        // 订单号 → 收款渠道（混合口径：主渠道码 + mixed 标记），供订单分录与退款 ORIGINAL 回填共用
        Map<String, String> orderChannel = new HashMap<>();
        for (Map<String, Object> o : orders) {
            String ch = mapPayMethod(str(o.get("payMethod")));
            if (ch != null) orderChannel.put(str(o.get("orderNo")), ch);
        }

        // 门店码 → 店名（批量）
        Map<String, String> storeNames = resolveStoreNames(collectStoreCodes(all));

        List<FinanceViewDTO.LedgerEntry> entries = new ArrayList<>();
        long seq = 0;
        for (Map<String, Object> item : all) {
            String kind = (String) item.get("kind");
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) item.get("row");
            String sc = str(row.get("storeCode"));
            if (!DataScope.canReadStore(sc)) continue; // 数据域二次收敛
            String storeName = storeNames.getOrDefault(sc, sc);

            switch (kind) {
                case "ORDER" -> entries.add(toOrderEntry(row, storeName, ++seq));
                case "REFUND" -> entries.add(toRefundEntry(row, storeName, ++seq, orderChannel));
                case "WRITEOFF" -> {
                    String cardNo = str(row.get("cardNo"));
                    if (cardNo == null || cardNo.isBlank()) continue; // 订单整单核销不计（无新资金）
                    entries.add(toWriteoffDepositEntry(row, storeName, ++seq));
                    entries.add(toWriteoffRevenueEntry(row, storeName, ++seq));
                }
                case "CARD_CANCEL" -> {
                    entries.add(toCcRefundEntry(row, storeName, ++seq));
                    entries.add(toCcDepositEntry(row, storeName, ++seq));
                    if (yuan(row.get("fee")) > 0) entries.add(toCcFeeEntry(row, storeName, ++seq)); // 免违约金（fee=0）不计
                }
                default -> { }
            }
        }
        // 日期升序、同日按序号
        entries.sort((a, b) -> a.date().compareTo(b.date()));
        return entries;
    }

    private FinanceViewDTO.LedgerEntry toOrderEntry(Map<String, Object> o, String storeName, long seq) {
        String orderNo = str(o.get("orderNo"));
        String memo = "订单收款 · " + nz(str(o.get("project")), orderNo);
        if (Boolean.TRUE.equals(o.get("mixed"))) memo = memo + "（混合支付）";
        return new FinanceViewDTO.LedgerEntry(
                "L" + seq, orderNo, dateOf(o.get("createdAt")), "RF-REVENUE", "IN",
                yuan(o.get("amount")), mapPayMethod(str(o.get("payMethod"))), "CASHIER", "ORDER",
                orderNo, storeName, memo, true, Boolean.TRUE.equals(o.get("mixed")));
    }

    private FinanceViewDTO.LedgerEntry toRefundEntry(Map<String, Object> r, String storeName, long seq,
                                                     Map<String, String> orderChannel) {
        String txnNo = str(r.get("txnNo"));
        return new FinanceViewDTO.LedgerEntry(
                "L" + seq, txnNo, dateOf(r.get("createdAt")), "RF-REFUND", "OUT",
                yuan(r.get("refundAmt")), resolveRefundChannel(str(r.get("channel")), str(r.get("orderNo")), orderChannel),
                "CASHIER", "REFUND",
                txnNo, storeName, "退款支出 · " + nz(str(r.get("customerName")), txnNo), false, false);
    }

    private FinanceViewDTO.LedgerEntry toWriteoffDepositEntry(Map<String, Object> w, String storeName, long seq) {
        String woId = str(w.get("writeoffId"));
        return new FinanceViewDTO.LedgerEntry(
                "L" + seq, woId + "-D", dateOf(w.get("createdAt")), "RF-DEPOSIT", "OUT",
                yuan(w.get("amount")), null, "ERP", "WRITEOFF",
                woId, storeName, "卡划扣（预收转出）· " + nz(str(w.get("project")), woId), true, false);
    }

    private FinanceViewDTO.LedgerEntry toWriteoffRevenueEntry(Map<String, Object> w, String storeName, long seq) {
        String woId = str(w.get("writeoffId"));
        return new FinanceViewDTO.LedgerEntry(
                "L" + seq, woId + "-R", dateOf(w.get("createdAt")), "RF-REVENUE", "IN",
                yuan(w.get("amount")), null, "ERP", "WRITEOFF",
                woId, storeName, "划扣确认收入 · " + nz(str(w.get("project")), woId), true, false);
    }

    /** 退卡实退：RF-REFUND / OUT / CASHIER（现金/转账流出；ORIGINAL 无原单号可查，保守留空）。 */
    private FinanceViewDTO.LedgerEntry toCcRefundEntry(Map<String, Object> c, String storeName, long seq) {
        String txnNo = str(c.get("txnNo"));
        return new FinanceViewDTO.LedgerEntry(
                "L" + seq, txnNo, dateOf(c.get("createdAt")), "RF-REFUND", "OUT",
                yuan(c.get("refundAmt")), resolveCcChannel(str(c.get("channel"))),
                "CASHIER", "REFUND",
                txnNo, storeName, "退款支出 · " + nz(str(c.get("customerName")), txnNo), false, false);
    }

    /** 退卡冲预收：RF-DEPOSIT / OUT / ERP（卡内余额全额转出预收池，内部结转 channel=null）。 */
    private FinanceViewDTO.LedgerEntry toCcDepositEntry(Map<String, Object> c, String storeName, long seq) {
        String txnNo = str(c.get("txnNo"));
        return new FinanceViewDTO.LedgerEntry(
                "L" + seq, txnNo + "-D", dateOf(c.get("createdAt")), "RF-DEPOSIT", "OUT",
                yuan(c.get("balance")), null, "ERP", "REFUND",
                txnNo, storeName, "退卡冲预收 · " + nz(str(c.get("customerName")), txnNo), true, false);
    }

    /** 退卡违约金：RF-REVENUE / IN / ERP（fee 转收入，禁忌免收 fee=0 时调用方已跳过）。 */
    private FinanceViewDTO.LedgerEntry toCcFeeEntry(Map<String, Object> c, String storeName, long seq) {
        String txnNo = str(c.get("txnNo"));
        return new FinanceViewDTO.LedgerEntry(
                "L" + seq, txnNo + "-F", dateOf(c.get("createdAt")), "RF-REVENUE", "IN",
                yuan(c.get("fee")), null, "ERP", "REFUND",
                txnNo, storeName, "退卡违约金收入 · " + nz(str(c.get("customerName")), txnNo), true, false);
    }

    // ==================== 会员卡余额 ====================

    /** 组装会员卡余额（已按登录人门店域收敛）。 */
    public FinanceViewDTO.CardBalanceBundle cardBalances(String storeCode) {
        List<Map<String, Object>> cards = fetchCards(storeCode);
        List<String> storeCodes = cards.stream().map(c -> str(c.get("storeCode"))).distinct().toList();
        Map<String, String> storeNames = resolveStoreNames(storeCodes);

        List<FinanceViewDTO.CardBalance> rows = new ArrayList<>();
        double stored = 0, gift = 0, timesValue = 0;
        long seq = 0;
        for (Map<String, Object> c : cards) {
            String sc = str(c.get("storeCode"));
            if (!DataScope.canReadStore(sc)) continue; // 数据域二次收敛
            int totalTimes = intOf(c.get("totalTimes"));
            int remainTimes = intOf(c.get("remainTimes"));
            double balance = yuan(c.get("balance"));
            String type = totalTimes > 0 ? "TIMES" : "STORED";

            FinanceViewDTO.CardBalance row = new FinanceViewDTO.CardBalance(
                    "C" + (++seq), str(c.get("cardNo")), str(c.get("customerName")), type,
                    balance, 0.0, totalTimes, remainTimes,
                    dateOf(c.get("createdAt")), mapCardStatus(str(c.get("status"))),
                    storeNames.getOrDefault(sc, sc));
            rows.add(row);

            stored += balance;
            if ("TIMES".equals(type)) timesValue += remainTimes * TIMES_UNIT_VALUE;
        }
        return new FinanceViewDTO.CardBalanceBundle(rows,
                round2(stored), round2(gift), round2(timesValue));
    }

    // ==================== 跨域取数（降级空集合） ====================

    /**
     * 拉取交易域资金流水四表 bundle（降级空 Map）。
     * 包级可见：{@link FinanceInternalOpsService} 双跑校对 / 历史回填复用同一取数口径。
     */
    Map<String, Object> fetchFlows(String storeCode, String from, String to) {
        try {
            UriComponentsBuilder b = UriComponentsBuilder
                    .fromHttpUrl(txnBaseUrl + "/api/txn/internal/finance-flows");
            if (storeCode != null && !storeCode.isBlank()) b.queryParam("storeCode", storeCode);
            if (from != null && !from.isBlank()) b.queryParam("from", from);
            if (to != null && !to.isBlank()) b.queryParam("to", to);
            ResponseEntity<Map<String, Object>> resp =
                    restTemplate.exchange(b.toUriString(), HttpMethod.GET, internalEntity(), MAP_TYPE);
            return resp.getBody() != null ? resp.getBody() : new HashMap<>();
        } catch (Exception e) {
            log.warn("拉取交易域资金流水失败（降级空台账）: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private List<Map<String, Object>> fetchCards(String storeCode) {
        try {
            UriComponentsBuilder b = UriComponentsBuilder
                    .fromHttpUrl(customerBaseUrl + "/api/customer/internal/card-balances");
            if (storeCode != null && !storeCode.isBlank()) b.queryParam("storeCode", storeCode);
            ResponseEntity<List<Map<String, Object>>> resp =
                    restTemplate.exchange(b.toUriString(), HttpMethod.GET, internalEntity(), LIST_MAP_TYPE);
            return resp.getBody() != null ? resp.getBody() : List.of();
        } catch (Exception e) {
            log.warn("拉取客户域卡余额失败（降级空卡列表）: {}", e.getMessage());
            return List.of();
        }
    }

    /** 门店码 → 店名批量解析（store 服务不可用时回落门店码本身）。 */
    public Map<String, String> resolveStoreNames(List<String> storeCodes) {
        List<String> codes = storeCodes.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (codes.isEmpty()) return Collections.emptyMap();
        try {
            UriComponentsBuilder b = UriComponentsBuilder
                    .fromHttpUrl(storeBaseUrl + "/api/stores/name-map");
            codes.forEach(c -> b.queryParam("codes", c));
            ResponseEntity<Map<String, Object>> resp =
                    restTemplate.exchange(b.toUriString(), HttpMethod.GET, internalEntity(), MAP_TYPE);
            Map<String, String> out = new LinkedHashMap<>();
            if (resp.getBody() != null) {
                resp.getBody().forEach((k, v) -> out.put(k, v == null ? k : v.toString()));
            }
            return out;
        } catch (Exception e) {
            log.warn("门店名解析失败（回落门店码），数量={} : {}", codes.size(), e.getMessage());
            Map<String, String> fallback = new LinkedHashMap<>();
            codes.forEach(c -> fallback.put(c, c));
            return fallback;
        }
    }

    private HttpEntity<Void> internalEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        return new HttpEntity<>(headers);
    }

    // ==================== 工具 ====================

    private List<String> collectStoreCodes(List<Map<String, Object>> all) {
        return all.stream()
                .map(i -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) i.get("row");
                    return str(row.get("storeCode"));
                })
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
    }

    /**
     * 订单收款支付方式（order_payment.pay_method）→ 前端渠道码直通；
     * 未知/空保守回落 null（落「未标记渠道」，不臆造）。
     */
    private String mapPayMethod(String payMethod) {
        if (payMethod == null) return null;
        return switch (payMethod) {
            case "cash", "card", "wxpay", "alipay", "balance" -> payMethod;
            default -> null;
        };
    }

    /**
     * 退款渠道解析：退款单渠道 CASH/TRANSFER 直映；ORIGINAL（原路退回）用原订单收款渠道回填
     * （RefundFlow 带 orderNo，取本批订单 orderNo→payMethod 映射）；原单不在区间/无流水时保守 null。
     */
    private String resolveRefundChannel(String channel, String orderNo, Map<String, String> orderChannel) {
        if (channel == null) return null;
        return switch (channel) {
            case "CASH" -> "cash";
            case "TRANSFER" -> "transfer";
            case "ORIGINAL" -> orderNo == null ? null : orderChannel.get(orderNo);
            default -> null;
        };
    }

    /**
     * 退卡渠道解析：CASH→cash、TRANSFER→transfer 直映；退卡单无 orderNo，ORIGINAL（原路退回）
     * 无法反查原收款渠道，保守 null（落「未标记渠道」，不臆造）。
     */
    private String resolveCcChannel(String channel) {
        if (channel == null) return null;
        return switch (channel) {
            case "CASH" -> "cash";
            case "TRANSFER" -> "transfer";
            default -> null;
        };
    }

    /** 会员卡中文状态 → 前端卡状态码（在用→NORMAL，退卡中→FROZEN，已退卡/已用完→DORMANT）。 */
    private String mapCardStatus(String status) {
        if (status == null) return "NORMAL";
        return switch (status) {
            case "在用" -> "NORMAL";
            case "退卡中" -> "FROZEN";
            default -> "DORMANT"; // 已退卡 / 已用完
        };
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String nz(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private static int intOf(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return 0; }
    }

    /** 分 → 元（两位小数）。 */
    private static double yuan(Object fen) {
        if (fen == null) return 0.0;
        long v = (fen instanceof Number n) ? n.longValue() : Long.parseLong(fen.toString());
        return round2(v / 100.0);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** ISO OffsetDateTime → yyyy-MM-dd（UTC 日界，与内部端点查询口径一致）。 */
    private static String dateOf(Object iso) {
        if (iso == null) return OffsetDateTime.now(ZoneOffset.UTC).toLocalDate().toString();
        try {
            return OffsetDateTime.parse(iso.toString()).atZoneSameInstant(ZoneOffset.UTC).toLocalDate().toString();
        } catch (Exception e) {
            String s = iso.toString();
            return s.length() >= 10 ? s.substring(0, 10) : s;
        }
    }
}
