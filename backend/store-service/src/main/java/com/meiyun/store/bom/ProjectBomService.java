package com.meiyun.store.bom;

import com.meiyun.store.consumable.ConsumableAuditRecorder;
import com.meiyun.store.consumable.ConsumableRepository;
import com.meiyun.store.consumable.ConsumableService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 项目用料配方（BOM）域服务（B10）：配方 upsert/启停、配方解析（门店行 &gt; 集团模板）、
 * 划扣后按项目自动扣料。
 *
 * <p>红线：BOM 配方解析与扣库均归 store 域，txn 不直读 project_bom/consumable_* 表，
 * 仅经内部端点 {@code POST /api/stores/internal/consumables/bom-deduct} 以系统身份回调。
 * 扣料复用 {@link ConsumableService#deduct}（行锁 + bizRef 幂等 + 移动平均定格成本）；
 * 库存不足/SKU 缺失抛 422/404 中文异常，由 txn 侧登记 bom_deduct_exception，<b>不阻断划扣</b>。
 * 无配方项目静默返回 skipped（医疗服务不强制配 BOM）。
 */
@Service
public class ProjectBomService {

    /** 集团模板的 store_code 取值（空串；唯一约束中与 NULL 语义不同，可稳定索引） */
    public static final String GROUP_TEMPLATE = "";

    private final ProjectBomRepository bomRepo;
    private final BomNoGenerator noGen;
    private final ConsumableRepository consumableRepo;
    private final ConsumableService consumableService;
    private final ConsumableAuditRecorder audit;

    public ProjectBomService(ProjectBomRepository bomRepo, BomNoGenerator noGen,
                             ConsumableRepository consumableRepo,
                             ConsumableService consumableService,
                             ConsumableAuditRecorder audit) {
        this.bomRepo = bomRepo;
        this.noGen = noGen;
        this.consumableRepo = consumableRepo;
        this.consumableService = consumableService;
        this.audit = audit;
    }

    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    /**
     * 新增/更新配方行（同 projectName+storeCode+skuCode 唯一，存在则更新用量/启用态）。
     * storeCode 传空串=集团模板；门店行校验 SKU 在本店已建档（集团模板 SKU 为全店统一编码，不逐店校验）。
     */
    @Transactional
    public ProjectBom upsert(String projectName, String storeCode, String skuCode,
                             Integer qty, Boolean enabled, String operator) {
        if (isBlank(projectName)) throw badReq("项目名称不能为空");
        if (isBlank(skuCode)) throw badReq("耗材 SKU 不能为空");
        if (qty == null || qty <= 0) throw badReq("标准用量必须为正整数");
        String pn = projectName.trim();
        String sc = storeCode == null ? GROUP_TEMPLATE : storeCode.trim();
        String sku = skuCode.trim();
        boolean en = enabled == null || enabled;

        if (!sc.isBlank()) {
            if (consumableRepo.findByStoreCodeAndSkuCode(sc, sku).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "本店不存在 SKU「" + sku + "」，请先在耗材台账建档后再配配方");
            }
        }

        ProjectBom b = bomRepo.findByProjectNameAndStoreCodeAndSkuCode(pn, sc, sku).orElse(null);
        boolean created = false;
        if (b == null) {
            b = new ProjectBom();
            b.setBomId(noGen.nextBomId());
            b.setProjectName(pn);
            b.setStoreCode(sc);
            b.setSkuCode(sku);
            b.setCreatedBy(operator);
            created = true;
        }
        b.setQty(qty);
        b.setEnabled(en);
        b.setUpdatedBy(operator);
        b.setUpdatedAt(OffsetDateTime.now());
        bomRepo.save(b);

        audit.record("BOM", b.getBomId(), operator, created ? "配方新增" : "配方更新",
                String.format("{\"projectName\":%s,\"storeCode\":%s,\"skuCode\":%s,"
                                + "\"qty\":%d,\"enabled\":%s}",
                        jsonStr(pn), jsonStr(sc.isBlank() ? "GROUP" : sc), jsonStr(sku), qty, en));
        return b;
    }

    /** 配方列表视图（storeCode 空串=集团模板；门店行尽力补 SKU 名称）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listBoms(String projectName, String storeCode) {
        String pn = isBlank(projectName) ? null : projectName.trim();
        // 前端显式传 "GROUP" 或空串时查集团模板；null 表示不限门店域
        String sc;
        if (storeCode == null) {
            sc = null;
        } else if (storeCode.isBlank() || "GROUP".equalsIgnoreCase(storeCode.trim())) {
            sc = GROUP_TEMPLATE;
        } else {
            sc = storeCode.trim();
        }
        List<ProjectBom> list = bomRepo.search(pn, sc);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProjectBom b : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bomId", b.getBomId());
            row.put("projectName", b.getProjectName());
            row.put("storeCode", b.getStoreCode().isBlank() ? "GROUP" : b.getStoreCode());
            row.put("skuCode", b.getSkuCode());
            row.put("qty", b.getQty());
            row.put("enabled", b.isEnabled());
            row.put("skuName", b.getStoreCode().isBlank() ? null
                    : consumableRepo.findByStoreCodeAndSkuCode(b.getStoreCode(), b.getSkuCode())
                    .map(c -> c.getName()).orElse(null));
            row.put("updatedBy", b.getUpdatedBy());
            row.put("updatedAt", b.getUpdatedAt());
            out.add(row);
        }
        return out;
    }

    /**
     * 划扣后按项目自动扣料（内部端点回调，系统身份）。
     * 解析配方（门店行 &gt; 集团模板，按 SKU 去重）→ 无配方静默 skipped；
     * 有配方则复用 ConsumableService.deduct（USE，bizRef 幂等），库存不足 422 中文抛出由调用方登记异常。
     *
     * @return 扣料结果（skipped=true 表示该项目未配 BOM，不属异常）
     */
    @Transactional
    public BomDeductResult resolveAndDeduct(String bizRef, String storeCode,
                                           String projectName, String operator) {
        if (isBlank(bizRef)) throw badReq("业务单号不能为空（幂等键）");
        if (isBlank(storeCode)) throw badReq("门店编码不能为空");
        if (isBlank(projectName)) return BomDeductResult.skip();

        List<ConsumableService.DeductLine> lines = resolveLines(projectName.trim(), storeCode.trim());
        if (lines.isEmpty()) {
            return BomDeductResult.skip();
        }
        List<ConsumableService.DeductLineResult> results =
                consumableService.deduct(bizRef, storeCode.trim(), lines, "USE", operator);
        long totalFen = results.stream().mapToLong(ConsumableService.DeductLineResult::amountFen).sum();
        audit.record("BOM", bizRef, operator, "划扣自动扣料",
                String.format("{\"projectName\":%s,\"storeCode\":%s,\"skuCount\":%d,"
                                + "\"totalCostFen\":%d,\"trigger\":\"WRITEOFF\"}",
                        jsonStr(projectName.trim()), jsonStr(storeCode.trim()), results.size(), totalFen));
        return new BomDeductResult(false, totalFen, results);
    }

    /**
     * 配方解析：取项目在该门店的启用行（门店行 + 集团行），按 SKU 去重——
     * 同 SKU 门店行覆盖集团行（门店优先），门店未覆盖的 SKU 回落集团模板用量。
     */
    private List<ConsumableService.DeductLine> resolveLines(String projectName, String storeCode) {
        List<ProjectBom> rows = bomRepo.findEnabledForMatch(projectName, storeCode);
        Map<String, ConsumableService.DeductLine> bySku = new LinkedHashMap<>();
        Set<String> storeSkus = new LinkedHashSet<>();
        // 先放门店行
        for (ProjectBom b : rows) {
            if (!b.getStoreCode().isBlank()) {
                bySku.put(b.getSkuCode(), new ConsumableService.DeductLine(
                        b.getSkuCode(), b.getQty(), "BOM 自动扣料（门店配方）"));
                storeSkus.add(b.getSkuCode());
            }
        }
        // 再回落集团模板（门店已覆盖的 SKU 不重复）
        for (ProjectBom b : rows) {
            if (b.getStoreCode().isBlank() && !storeSkus.contains(b.getSkuCode())) {
                bySku.put(b.getSkuCode(), new ConsumableService.DeductLine(
                        b.getSkuCode(), b.getQty(), "BOM 自动扣料（集团模板）"));
            }
        }
        return new ArrayList<>(bySku.values());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** BOM 自动扣料结果：skipped=无配方静默跳过；totalAmountFen/lines 为实际出库定格成本（分） */
    public record BomDeductResult(boolean skipped, long totalAmountFen,
                                  List<ConsumableService.DeductLineResult> lines) {
        static BomDeductResult skip() {
            return new BomDeductResult(true, 0L, List.of());
        }
    }
}
