package com.meiyun.txn;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BOM 扣料异常清单（B10，DESIGN §6.4）：划扣提交后自动扣料失败的异常单在此追溯处理。
 *
 * <p>清单查询走 {@code inventory:consumable:view}（数据域强制注入，门店角色只见本店）；
 * 「重试扣料」「标记已处理」走 {@code inventory:consumable:edit}，操作人取登录人工号（请求体不可信）。
 * 异常单不阻断划扣：门店补货后点重试（幂等安全），或走领用审批手工补单后标记已处理。
 */
@RestController
@RequestMapping("/api/txn/bom-exceptions")
public class BomExceptionController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BomDeductService service;
    private final ApptRefNameResolver names;

    public BomExceptionController(BomDeductService service, ApptRefNameResolver names) {
        this.service = service;
        this.names = names;
    }

    /** 异常清单：status=PENDING/RESOLVED 可选、storeCode 可选（数据域强制注入）。 */
    @GetMapping
    @RequirePerm("inventory:consumable:view")
    public List<ExcView> list(@RequestParam(required = false) String status,
                              @RequestParam(required = false) String storeCode) {
        List<BomDeductException> list = service.list(status, storeCode);
        return toViews(list);
    }

    /** 重试扣料：补货/修复档案后重新按 BOM 出库，成功自动置「已处理」。 */
    @PostMapping("/{excId}/retry")
    @RequirePerm("inventory:consumable:edit")
    public ExcView retry(@PathVariable String excId) {
        BomDeductException e = service.retry(excId, DataScope.currentActor());
        return toViews(List.of(e)).get(0);
    }

    /** 标记已处理：已走领用审批手工补单/线下补料后人工销项。 */
    @PostMapping("/{excId}/resolve")
    @RequirePerm("inventory:consumable:edit")
    public ExcView resolve(@PathVariable String excId) {
        BomDeductException e = service.markResolved(excId, DataScope.currentActor());
        return toViews(List.of(e)).get(0);
    }

    // ---- 读模型富化（门店名） ----

    private List<ExcView> toViews(List<BomDeductException> list) {
        if (list.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, String> storeNames = names.storeNames(
                list.stream().map(BomDeductException::getStoreCode).distinct().toList());
        List<ExcView> out = new ArrayList<>(list.size());
        for (BomDeductException e : list) {
            out.add(new ExcView(
                    e.getExcId(), e.getWriteoffId(),
                    e.getStoreCode(), storeNames.getOrDefault(e.getStoreCode(), e.getStoreCode()),
                    e.getProjectName(), e.getReason(), e.getStatus(),
                    e.getFailCount(), e.getCreatedAt(), e.getResolvedAt(), e.getResolvedBy(),
                    parseShortages(e.getDetailJson())));
        }
        return out;
    }

    /**
     * 缺料明细反序列化（B34）：detail_json 由 store 侧结构化 422 回传，可能为空
     * （SKU 未建档 / 库存服务不可用 / B34 之前登记的历史单）→ 返回空数组，前端只需判空。
     */
    private static List<ShortageView> parseShortages(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(detailJson, new TypeReference<List<ShortageView>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /** 异常单读模型（金额/库存明细在 store 侧台账查，本模型聚焦追溯处理）。 */
    public record ExcView(
            String excId, String writeoffId,
            String storeCode, String storeName,
            String projectName, String reason, String status,
            int failCount,
            java.time.OffsetDateTime createdAt,
            java.time.OffsetDateTime resolvedAt,
            String resolvedBy,
            List<ShortageView> shortages) {}

    /** 缺料行：需求量 needQty &gt; 现存量 stockQty（无库存记录记 0）。 */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ShortageView(String skuCode, String skuName, int needQty, int stockQty, String unit) {}
}
