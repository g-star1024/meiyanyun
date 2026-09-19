package com.meiyun.txn;

import com.meiyun.security.DataScope;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 统一异常中心聚合服务（B63 卡2 L83）：只读归集三源异常单据为 {@link ExceptionCenterItem}。
 *
 * <ul>
 *   <li>BOM_DEDUCT：本地 {@link BomDeductService#list}（bom_deduct_exception，已有真实 PENDING 单）；</li>
 *   <li>WRITEOFF：本地 writeoff_record status=ABNORMAL（当前无数据则诚实空态，不造数）；</li>
 *   <li>FIN_ABNORMAL：{@link FinanceAbnormalBillClient} 跨服务只读拉取，finance 不可用软降级为空。</li>
 * </ul>
 *
 * <p>安全口径：本地两源经 DataScope Specification 收敛；FIN 以系统身份拉全量后<b>逐行
 * {@link DataScope#canReadStore} 二次判定</b>，杜绝越权。本服务零写操作，处置一律按源
 * {@code disposeRoute} 跳既有处置视图（/m2-inventory、/writeoff、/m6-abnormal）。
 */
@Service
public class ExceptionCenterService {

    public static final String SRC_BOM = "BOM_DEDUCT";
    public static final String SRC_WRITEOFF = "WRITEOFF";
    public static final String SRC_FIN = "FIN_ABNORMAL";

    public static final String ST_PENDING = "PENDING";
    public static final String ST_PROCESSING = "PROCESSING";
    public static final String ST_CLOSED = "CLOSED";

    private static final String TYPE_BUSINESS = "BUSINESS";
    private static final String LEVEL_HIGH = "HIGH";
    private static final String LEVEL_MEDIUM = "MEDIUM";
    private static final String LEVEL_LOW = "LOW";

    private static final String ROUTE_INVENTORY = "/m2-inventory";
    private static final String ROUTE_WRITEOFF = "/writeoff";
    private static final String ROUTE_ABNORMAL = "/m6-abnormal";

    private final BomDeductService bomDeductService;
    private final BomDeductExceptionRepository bomExcRepo;
    private final WriteoffRepository writeoffRepo;
    private final FinanceAbnormalBillClient finClient;
    private final ApptRefNameResolver names;

    public ExceptionCenterService(BomDeductService bomDeductService,
                                  BomDeductExceptionRepository bomExcRepo,
                                  WriteoffRepository writeoffRepo,
                                  FinanceAbnormalBillClient finClient,
                                  ApptRefNameResolver names) {
        this.bomDeductService = bomDeductService;
        this.bomExcRepo = bomExcRepo;
        this.writeoffRepo = writeoffRepo;
        this.finClient = finClient;
        this.names = names;
    }

    /**
     * 统一异常列表。
     *
     * @param source    BOM_DEDUCT/WRITEOFF/FIN_ABNORMAL，可空=全部
     * @param status    统一状态 PENDING/PROCESSING/CLOSED，可空；各源原状态由映射层转换后内存过滤
     * @param storeCode 门店码，可空；非空先做越权断言（对齐既有查询口径）
     */
    public List<ExceptionCenterItem> list(String source, String status, String storeCode) {
        String src = trim(source);
        String st = trim(status);
        String sc = trim(storeCode);
        if (sc != null && !DataScope.canReadStore(sc)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        List<ExceptionCenterItem> all = new ArrayList<>();
        if (src == null || SRC_BOM.equals(src)) {
            all.addAll(fromBom(sc));
        }
        if (src == null || SRC_WRITEOFF.equals(src)) {
            all.addAll(fromWriteoff(sc));
        }
        if (src == null || SRC_FIN.equals(src)) {
            all.addAll(fromFin(sc));
        }
        if (st != null) {
            all.removeIf(i -> !st.equals(i.status()));
        }
        // 门店名 / 指派人姓名富化（内部端点批量，失败各自降级空 Map）
        enrich(all);
        all.sort(Comparator.comparing(ExceptionCenterItem::occurredAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ExceptionCenterItem::id));
        return all;
    }

    /** 统一详情：按 id 前缀分发到源；不存在/越权统一 404 中文（不泄露存在性）。 */
    public ExceptionCenterItem detail(String id) {
        if (id == null || id.isBlank()) {
            throw notFound();
        }
        ExceptionCenterItem item;
        if (id.startsWith("BOM:BEX")) {
            String excId = id.substring(4);
            BomDeductException e = bomExcRepo.findById(excId).orElseThrow(ExceptionCenterService::notFound);
            if (!DataScope.canReadStore(e.getStoreCode())) {
                throw notFound();
            }
            item = toBomItem(e);
        } else if (id.startsWith("WO:WO")) {
            String writeoffId = id.substring(3);
            WriteoffRecord w = writeoffRepo.findById(writeoffId)
                    .filter(x -> "ABNORMAL".equals(x.getStatus()))
                    .orElseThrow(ExceptionCenterService::notFound);
            if (!DataScope.canReadStore(w.getStoreCode())) {
                throw notFound();
            }
            item = toWriteoffItem(w);
        } else if (id.startsWith("FIN:AB")) {
            String billNo = id.substring(4);
            item = finClient.listBills(null, null, null).stream()
                    .filter(b -> billNo.equals(b.billNo()))
                    .filter(b -> DataScope.canReadStore(b.storeCode()))
                    .map(ExceptionCenterService::toFinItem)
                    .findFirst()
                    .orElseThrow(ExceptionCenterService::notFound);
        } else {
            throw notFound();
        }
        List<ExceptionCenterItem> holder = new ArrayList<>(1);
        holder.add(item);
        enrich(holder);
        return holder.get(0);
    }

    // ==================== BOM_DEDUCT ====================

    private List<ExceptionCenterItem> fromBom(String storeCode) {
        return bomDeductService.list(null, storeCode).stream()
                .map(this::toBomItem)
                .toList();
    }

    private ExceptionCenterItem toBomItem(BomDeductException e) {
        boolean resolved = BomDeductException.ST_RESOLVED.equals(e.getStatus());
        List<ExceptionCenterItem.Node> timeline = new ArrayList<>();
        timeline.add(node("系统",
                "自动扣料失败已登记（失败 " + Math.max(1, e.getFailCount()) + " 次）：" + e.getReason(),
                e.getCreatedAt()));
        if (resolved && e.getResolvedAt() != null) {
            timeline.add(node(e.getResolvedBy(), "补货后重试成功/人工标记已处理", e.getResolvedAt()));
        }
        String title = "BOM 自动扣料失败：" + nz(e.getProjectName(), e.getWriteoffId());
        return new ExceptionCenterItem(
                "BOM:" + e.getExcId(), e.getExcId(), SRC_BOM, e.getStoreCode(), null,
                TYPE_BUSINESS, e.getFailCount() >= 3 ? LEVEL_HIGH : LEVEL_MEDIUM,
                title, e.getReason(),
                resolved ? ST_CLOSED : ST_PENDING,
                resolved ? e.getResolvedBy() : null,
                e.getCreatedAt(), e.getResolvedAt(),
                List.copyOf(timeline), ROUTE_INVENTORY);
    }

    // ==================== WRITEOFF ====================

    private List<ExceptionCenterItem> fromWriteoff(String storeCode) {
        Specification<WriteoffRecord> spec = DataScope.<WriteoffRecord>storeSpec("storeCode")
                .and((root, q, cb) -> cb.equal(root.get("status"), "ABNORMAL"));
        if (storeCode != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        return writeoffRepo.findAll(spec).stream()
                .map(this::toWriteoffItem)
                .toList();
    }

    private ExceptionCenterItem toWriteoffItem(WriteoffRecord w) {
        // ABNORMAL 无处置时间/处置人字段（三悬空），中心诚实展示登记节点，处置回原划扣视图
        String desc = nz(w.getAbnormalReason(), "划扣状态异常，请核对双签与项目次数后到划扣核销台处理");
        List<ExceptionCenterItem.Node> timeline = List.of(
                node(nz(w.getOperator(), "系统"), "划扣单标记为异常：" + desc, w.getCreatedAt()));
        String title = "划扣核销异常：" + nz(w.getProject(), w.getWriteoffId());
        return new ExceptionCenterItem(
                "WO:" + w.getWriteoffId(), w.getWriteoffId(), SRC_WRITEOFF, w.getStoreCode(), null,
                TYPE_BUSINESS, LEVEL_HIGH,
                title, desc, ST_PENDING, w.getOperator(),
                w.getCreatedAt(), null, timeline, ROUTE_WRITEOFF);
    }

    // ==================== FIN_ABNORMAL ====================

    private List<ExceptionCenterItem> fromFin(String storeCode) {
        return finClient.listBills(null, null, storeCode).stream()
                .filter(b -> DataScope.canReadStore(b.storeCode()))
                .map(ExceptionCenterService::toFinItem)
                .toList();
    }

    private static ExceptionCenterItem toFinItem(FinanceAbnormalBillClient.AbnormalBill b) {
        boolean rejected = "REJECTED".equals(b.status());
        boolean disposed = "DISPOSED".equals(b.status());
        String unified = rejected || disposed ? ST_CLOSED : ST_PENDING;
        long abs = Math.abs(b.amountFen());
        String level = abs >= 2_000_000L ? LEVEL_HIGH : abs >= 500_000L ? LEVEL_MEDIUM : LEVEL_LOW;

        List<ExceptionCenterItem.Node> timeline = new ArrayList<>();
        timeline.add(node(b.createdBy(),
                finTypeLabel(b.type()) + "登记，金额 " + money(b.amountFen()) + "：" + b.reason(),
                b.createdAt()));
        if (b.approvedAt() != null) {
            String text = rejected
                    ? "审批驳回：" + nz(b.reason(), "")
                    : "审批通过（" + nz(b.approvalNo(), "") + "），待财务处置动账";
            timeline.add(node(b.reviewer(), text, b.approvedAt()));
        }
        if (disposed && b.disposedAt() != null) {
            timeline.add(node(b.reviewer(), "已处置，ADJUST 资金分录入账", b.disposedAt()));
        }

        String title = finTypeLabel(b.type()) + "异常账单 " + b.billNo();
        String description = finTypeLabel(b.type()) + "，金额 " + money(b.amountFen())
                + "，原因：" + b.reason();
        return new ExceptionCenterItem(
                "FIN:" + b.billNo(), b.billNo(), SRC_FIN, b.storeCode(), null,
                TYPE_BUSINESS, level, title, description, unified,
                disposed || rejected ? b.reviewer() : b.createdBy(),
                b.createdAt(), disposed ? b.disposedAt() : rejected ? b.approvedAt() : null,
                List.copyOf(timeline), ROUTE_ABNORMAL);
    }

    private static String finTypeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "SHORT" -> "短款";
            case "LONG" -> "长款";
            case "WRONG" -> "错账";
            default -> "异常账务";
        };
    }

    // ==================== 富化 ====================

    private void enrich(List<ExceptionCenterItem> items) {
        if (items.isEmpty()) return;
        Set<String> storeCodes = collect(items, ExceptionCenterItem::storeCode);
        Set<String> staffIds = new LinkedHashSet<>();
        for (ExceptionCenterItem i : items) {
            for (ExceptionCenterItem.Node n : i.timeline()) {
                addStaff(staffIds, n.by());
            }
            addStaff(staffIds, i.assignee());
        }
        Map<String, String> storeNames = names.storeNames(storeCodes);
        Map<String, String> staffNames = names.staffNames(staffIds);
        for (int idx = 0; idx < items.size(); idx++) {
            ExceptionCenterItem i = items.get(idx);
            String sn = storeNames.get(i.storeCode());
            List<ExceptionCenterItem.Node> nodes = i.timeline().stream()
                    .map(n -> new ExceptionCenterItem.Node(displayName(n.by(), staffNames), n.text(), n.at()))
                    .toList();
            ExceptionCenterItem enriched = new ExceptionCenterItem(
                    i.id(), i.no(), i.source(), i.storeCode(),
                    sn != null ? sn : i.storeCode(),
                    i.type(), i.level(), i.title(), i.description(), i.status(),
                    displayName(i.assignee(), staffNames),
                    i.occurredAt(), i.closedAt(), nodes, i.disposeRoute());
            items.set(idx, enriched);
        }
    }

    private static Set<String> collect(List<ExceptionCenterItem> items,
                                       java.util.function.Function<ExceptionCenterItem, String> f) {
        Set<String> out = new LinkedHashSet<>();
        for (ExceptionCenterItem i : items) {
            String v = f.apply(i);
            if (v != null && !v.isBlank()) out.add(v);
        }
        return out;
    }

    private static void addStaff(Set<String> ids, String who) {
        if (who == null || who.isBlank() || "系统".equals(who)) return;
        ids.add(who);
    }

    private static String displayName(String idOrName, Map<String, String> nameMap) {
        if (idOrName == null || idOrName.isBlank()) return null;
        if ("系统".equals(idOrName)) return "系统";
        return nameMap.getOrDefault(idOrName, idOrName);
    }

    // ==================== 工具 ====================

    private static String money(long fen) {
        long abs = Math.abs(fen);
        long yuan = abs / 100;
        long cents = abs % 100;
        String sign = fen < 0 ? "-" : "";
        return sign + "¥" + String.format("%,d", yuan) + (cents != 0 ? String.format(".%02d", cents) : "");
    }

    private static String nz(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static ExceptionCenterItem.Node node(String by, String text, OffsetDateTime at) {
        return new ExceptionCenterItem.Node(by, text, at);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "异常单据不存在或无权查看");
    }
}
