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

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** 核销状态白名单（与 txn writeoff_record 口径一致），入参校验前置，避免非法值被降级吞成空集。 */
    private static final Set<String> WRITEOFF_STATUS_WHITELIST = Set.of("DONE", "ABNORMAL", "VOID");

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
            double giftBalance = yuan(c.get("giftBalance"));
            String type = totalTimes > 0 ? "TIMES" : "STORED";

            FinanceViewDTO.CardBalance row = new FinanceViewDTO.CardBalance(
                    "C" + (++seq), str(c.get("cardNo")), str(c.get("customerName")), type,
                    balance, giftBalance, totalTimes, remainTimes,
                    dateOf(c.get("createdAt")), mapCardStatus(str(c.get("status"))),
                    storeNames.getOrDefault(sc, sc));
            rows.add(row);

            stored += balance;
            gift += giftBalance;
            if ("TIMES".equals(type)) timesValue += remainTimes * TIMES_UNIT_VALUE;
        }
        return new FinanceViewDTO.CardBalanceBundle(rows,
                round2(stored), round2(gift), round2(timesValue));
    }

    // ==================== 单卡时间线（B24 卡2） ====================

    /**
     * 单卡余额变动时间线（已按登录人门店域收敛）。
     * 拉 customer 内部端点（卡快照 + card_ledger 全量流水，Long 分），出口换算元、解析店名；
     * 卡不存在 / customer 不可用（404/5xx）由调用端点透传/降级——本方法遇异常返回 null，
     * 控制器据此回 404（不泄露越权卡号是否存在，统一「卡不存在或无权查看」）。
     */
    public FinanceViewDTO.CardTimeline cardTimeline(String cardNo) {
        if (cardNo == null || cardNo.isBlank()) {
            throw new IllegalArgumentException("卡号不能为空");
        }
        Map<String, Object> bundle = fetchCardLedger(cardNo.trim());
        if (bundle == null || bundle.isEmpty()) return null;
        String sc = str(bundle.get("storeCode"));
        if (!DataScope.canReadStore(sc)) return null; // 越权与不存在同响应，防卡号探测
        Map<String, String> storeNames = resolveStoreNames(List.of(sc));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ledger = (List<Map<String, Object>>) bundle.getOrDefault("ledger", List.of());
        List<FinanceViewDTO.CardTxn> txns = new ArrayList<>();
        for (Map<String, Object> l : ledger) {
            txns.add(new FinanceViewDTO.CardTxn(
                    longOf(l.get("ledgerId")), str(l.get("changeType")),
                    yuan(l.get("amount")), yuan(l.get("balanceAfter")),
                    yuan(l.get("giftAmount")), yuan(l.get("giftAfter")),
                    str(l.get("bizRef")), str(l.get("orderNo")),
                    str(l.get("operator")), dateTimeOf(l.get("createdAt"))));
        }
        int totalTimes = intOf(bundle.get("totalTimes"));
        return new FinanceViewDTO.CardTimeline(
                str(bundle.get("cardNo")), str(bundle.get("customerId")), str(bundle.get("customerName")),
                str(bundle.get("cardItem")), nz(sc, ""), storeNames.getOrDefault(sc, nz(sc, "")),
                str(bundle.get("cardType")), str(bundle.get("productCode")),
                totalTimes > 0 ? "TIMES" : "STORED",
                yuan(bundle.get("balance")), yuan(bundle.get("giftBalance")),
                totalTimes, intOf(bundle.get("remainTimes")),
                mapCardStatus(str(bundle.get("status"))), txns);
    }

    // ==================== 核销双签明细（B24 卡2） ====================

    /**
     * 核销双签明细（已按登录人门店域逐行收敛）。
     * 拉 txn 内部端点（不固化 status 的全状态核销 + 双签留痕），出口换算元、解析店名与客户名；
     * 过滤参数透传 txn：status（DONE/ABNORMAL/VOID）/cardNo/customerId/keyword/from/to。
     * 整单核销（cardNo 空）customerId 可空，客户名仅批量解析非空 id（解析失败回落客户号）。
     */
    public List<FinanceViewDTO.WriteoffDetail> writeoffDetails(String storeCode, String status, String cardNo,
                                                               String customerId, String keyword,
                                                               String from, String to) {
        // 非法 status 属于客户端错误，必须在降级取数前快速 400——否则跨域调用的 400 会被「被调不可用降级空集」吞成 200 空列表
        if (status != null && !status.isBlank()) {
            String st = status.trim().toUpperCase();
            if (!WRITEOFF_STATUS_WHITELIST.contains(st)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "核销状态参数 status 非法，仅支持 DONE（已核销）/ABNORMAL（异常）/VOID（已作废）：" + status);
            }
        }
        List<Map<String, Object>> rows = fetchWriteoffDetails(storeCode, status, cardNo, customerId, keyword, from, to);
        List<String> storeCodes = rows.stream().map(r -> str(r.get("storeCode"))).distinct().toList();
        Map<String, String> storeNames = resolveStoreNames(storeCodes);
        List<String> customerIds = rows.stream().map(r -> str(r.get("customerId")))
                .filter(s -> s != null && !s.isBlank()).distinct().toList();
        Map<String, String> customerNames = resolveCustomerNames(customerIds);

        List<FinanceViewDTO.WriteoffDetail> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String sc = str(r.get("storeCode"));
            if (!DataScope.canReadStore(sc)) continue; // 数据域二次收敛
            String cid = str(r.get("customerId"));
            out.add(new FinanceViewDTO.WriteoffDetail(
                    str(r.get("writeoffId")), str(r.get("orderNo")), nz(str(r.get("cardNo")), ""),
                    nz(sc, ""), storeNames.getOrDefault(sc, nz(sc, "")),
                    nz(cid, ""), cid == null ? "" : customerNames.getOrDefault(cid, cid),
                    str(r.get("project")), intOf(r.get("timesUsed")) == 0 ? 1 : intOf(r.get("timesUsed")),
                    yuan(r.get("amount")), str(r.get("status")), str(r.get("operator")),
                    str(r.get("sign1")), str(r.get("sign2")), str(r.get("abnormalReason")),
                    dateOf(r.get("createdAt"))));
        }
        return out;
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
                    restTemplate.exchange(b.build().encode().toUri(), HttpMethod.GET, internalEntity(), MAP_TYPE);
            return resp.getBody() != null ? resp.getBody() : new HashMap<>();
        } catch (Exception e) {
            log.warn("拉取交易域资金流水失败（降级空台账）: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 拉取交易域现金日结投影（B7 三方对账「现金交接」方；降级 null，由调用方诚实标记不可用）。
     * txn 侧按 Asia/Shanghai 自然日聚合「现金交接」类已完成双签工单，金额 Long「分」。
     * 包级可见：三方对账服务复用同一系统身份取数边界。
     */
    Map<String, Object> fetchCashSettle(String date, String storeCode) {
        try {
            UriComponentsBuilder b = UriComponentsBuilder
                    .fromHttpUrl(txnBaseUrl + "/api/txn/internal/cash-settle");
            if (date != null && !date.isBlank()) b.queryParam("date", date);
            if (storeCode != null && !storeCode.isBlank()) b.queryParam("storeCode", storeCode);
            ResponseEntity<Map<String, Object>> resp =
                    restTemplate.exchange(b.build().encode().toUri(), HttpMethod.GET, internalEntity(), MAP_TYPE);
            return resp.getBody();
        } catch (Exception e) {
            log.warn("拉取交易域现金日结失败（三方对账现金方标记不可用）date={} : {}", date, e.getMessage());
            return null;
        }
    }

    private List<Map<String, Object>> fetchCards(String storeCode) {
        try {
            UriComponentsBuilder b = UriComponentsBuilder
                    .fromHttpUrl(customerBaseUrl + "/api/customer/internal/card-balances");
            if (storeCode != null && !storeCode.isBlank()) b.queryParam("storeCode", storeCode);
            ResponseEntity<List<Map<String, Object>>> resp =
                    restTemplate.exchange(b.build().encode().toUri(), HttpMethod.GET, internalEntity(), LIST_MAP_TYPE);
            return resp.getBody() != null ? resp.getBody() : List.of();
        } catch (Exception e) {
            log.warn("拉取客户域卡余额失败（降级空卡列表）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 拉取客户域单卡时间线（B24 卡2；降级 null——404 卡不存在 / 5xx 不可用均由控制器按 404 诚实返回）。
     * 路径变量需 URL 编码防注入/特殊字符。
     */
    private Map<String, Object> fetchCardLedger(String cardNo) {
        try {
            URI url = UriComponentsBuilder.fromHttpUrl(customerBaseUrl)
                    .pathSegment("api", "customer", "internal", "cards", cardNo, "ledger")
                    .build().encode().toUri();
            ResponseEntity<Map<String, Object>> resp =
                    restTemplate.exchange(url, HttpMethod.GET, internalEntity(), MAP_TYPE);
            return resp.getBody();
        } catch (Exception e) {
            log.warn("拉取客户域单卡流水失败（按不存在处理）cardNo={} : {}", cardNo, e.getMessage());
            return null;
        }
    }

    /**
     * 拉取交易域核销双签明细（B24 卡2；降级空列表）。全状态、多过滤，参数均可选。
     */
    private List<Map<String, Object>> fetchWriteoffDetails(String storeCode, String status, String cardNo,
                                                           String customerId, String keyword,
                                                           String from, String to) {
        try {
            UriComponentsBuilder b = UriComponentsBuilder
                    .fromHttpUrl(txnBaseUrl + "/api/txn/internal/writeoff-details");
            if (storeCode != null && !storeCode.isBlank()) b.queryParam("storeCode", storeCode);
            if (status != null && !status.isBlank()) b.queryParam("status", status);
            if (cardNo != null && !cardNo.isBlank()) b.queryParam("cardNo", cardNo);
            if (customerId != null && !customerId.isBlank()) b.queryParam("customerId", customerId);
            if (keyword != null && !keyword.isBlank()) b.queryParam("keyword", keyword);
            if (from != null && !from.isBlank()) b.queryParam("from", from);
            if (to != null && !to.isBlank()) b.queryParam("to", to);
            ResponseEntity<List<Map<String, Object>>> resp =
                    restTemplate.exchange(b.build().encode().toUri(), HttpMethod.GET, internalEntity(), LIST_MAP_TYPE);
            return resp.getBody() != null ? resp.getBody() : List.of();
        } catch (Exception e) {
            log.warn("拉取交易域核销明细失败（降级空列表）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 客户号 → 客户姓名批量解析（customer /api/customer/name-map，internal:name-map；
     * 服务不可用/超时回落空 Map，调用方保守回落客户号本身）。
     */
    private Map<String, String> resolveCustomerNames(List<String> customerIds) {
        List<String> ids = customerIds.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (ids.isEmpty()) return Collections.emptyMap();
        try {
            UriComponentsBuilder b = UriComponentsBuilder
                    .fromHttpUrl(customerBaseUrl + "/api/customer/name-map");
            ids.forEach(i -> b.queryParam("ids", i));
            ResponseEntity<Map<String, Object>> resp =
                    restTemplate.exchange(b.build().encode().toUri(), HttpMethod.GET, internalEntity(), MAP_TYPE);
            Map<String, String> out = new LinkedHashMap<>();
            if (resp.getBody() != null) {
                resp.getBody().forEach((k, v) -> out.put(k, v == null ? k : v.toString()));
            }
            return out;
        } catch (Exception e) {
            log.warn("客户名解析失败（回落客户号），数量={} : {}", ids.size(), e.getMessage());
            Map<String, String> fallback = new LinkedHashMap<>();
            ids.forEach(i -> fallback.put(i, i));
            return fallback;
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
                    restTemplate.exchange(b.build().encode().toUri(), HttpMethod.GET, internalEntity(), MAP_TYPE);
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

    /**
     * 门店存在性严格校验（封账等不可逆写操作前置用）：调 store 服务 name-map，
     * HTTP 200 且返回 Map 含该码 → 存在；200 但缺 key → 确定不存在（findAllById 只回存在门店）；
     * 调用异常（store 服务不可用/超时）→ 抛 StoreLookupException，调用方按「资金安全反向失败即中止」保守拒绝，
     * 不把服务故障误判为门店不存在，也不静默放行。
     */
    public boolean storeExists(String storeCode) {
        if (storeCode == null || storeCode.isBlank()) return false;
        String code = storeCode.trim();
        try {
            UriComponentsBuilder b = UriComponentsBuilder
                    .fromHttpUrl(storeBaseUrl + "/api/stores/name-map")
                    .queryParam("codes", code);
            ResponseEntity<Map<String, Object>> resp =
                    restTemplate.exchange(b.build().encode().toUri(), HttpMethod.GET, internalEntity(), MAP_TYPE);
            return resp.getBody() != null && resp.getBody().containsKey(code);
        } catch (Exception e) {
            log.warn("门店存在性校验失败（保守中止）store={} : {}", code, e.getMessage());
            throw new StoreLookupException("门店服务暂不可用，封账已中止，请稍后重试（门店 " + code + "）");
        }
    }

    /** 门店服务查询失败（区分于「门店不存在」）：触发保守中止而非静默放行。 */
    public static class StoreLookupException extends RuntimeException {
        public StoreLookupException(String message) {
            super(message);
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

    private static long longOf(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0L; }
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

    /** ISO OffsetDateTime → yyyy-MM-dd HH:mm:ss（业务本地时区 Asia/Shanghai，供卡流水时间线展示）。 */
    private static String dateTimeOf(Object iso) {
        if (iso == null) return "";
        try {
            return OffsetDateTime.parse(iso.toString())
                    .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return iso.toString();
        }
    }
}
