package com.meiyun.store.bom;

import com.meiyun.security.RequirePerm;
import com.meiyun.store.consumable.ConsumableService;
import com.meiyun.store.consumable.InsufficientStockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BOM 自动扣料内部端点（B10，服务间调用专用）：txn 双签划扣 DONE 事务提交后以系统身份回调。
 *
 * <p>红线边界：配方解析与扣库是划扣的下游自动动作，不对业务页面开放——仅 X-Internal-Token
 * 系统身份（perms=["*"]）放行，普通登录人无 {@code internal:consumable-write} 权限 → 403。
 * txn 域不直读 project_bom/consumable_* 表，由 store 域按项目名解析配方（门店行 &gt; 集团模板）
 * 并复用 ConsumableService.deduct 出库。<b>扣料失败不阻断划扣</b>：422 库存不足/404 SKU 缺失
 * 中文透传给 txn，由 txn 登记 bom_deduct_exception；无配方项目静默 skipped（非异常）。
 * 幂等：bizRef（{@code BOM:{writeoffId}}）+ SKU 已存在出库流水即跳过，重试不双扣。
 */
@RestController
@RequestMapping("/api/stores/internal/consumables")
public class InternalBomController {

    private final ProjectBomService service;

    public InternalBomController(ProjectBomService service) {
        this.service = service;
    }

    /**
     * 按项目自动扣料：POST /api/stores/internal/consumables/bom-deduct。
     * 返回 skipped=true 表示该项目未配 BOM（静默跳过）；否则返回各 SKU 定格单价与金额（分），
     * 供 txn 写耗材成本 outbox（USE → TK-MATERIAL）。
     */
    @PostMapping("/bom-deduct")
    @RequirePerm("internal:consumable-write")
    public Map<String, Object> bomDeduct(@RequestBody BomDeductCmd cmd) {
        if (cmd == null) throw ProjectBomService.badReq("请求体不能为空");
        ProjectBomService.BomDeductResult result = service.resolveAndDeduct(
                cmd.bizRef(), cmd.storeCode(), cmd.projectName(), cmd.operator());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bizRef", cmd.bizRef());
        out.put("projectName", cmd.projectName());
        out.put("storeCode", cmd.storeCode());
        out.put("skipped", result.skipped());
        out.put("lineCount", result.lines().size());
        out.put("totalAmountFen", result.totalAmountFen());
        out.put("lines", result.lines().stream().map(l -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("skuCode", l.skuCode());
            row.put("name", l.name());
            row.put("qty", l.qty());
            row.put("unitCostFen", l.unitCostFen());
            row.put("amountFen", l.amountFen());
            row.put("stockAfter", l.stockAfter());
            return row;
        }).toList());
        return out;
    }

    /** 自动扣料入参：bizRef=BOM:{writeoffId}（幂等键）/storeCode/projectName 勾兑键/operator 划扣操作人 */
    public record BomDeductCmd(String bizRef, String storeCode, String projectName, String operator) {}

    /**
     * 库存不足 422（B34）：<b>仅本控制器局部生效</b>，在原中文 message 之外追加结构化 shortages 数组，
     * 供 txn 落 {@code bom_deduct_exception.detail_json}（缺哪个 SKU、需多少、还剩多少）。
     *
     * <p>store-service 无全局异常处理器，故不改全局错误体、不影响其它端点（含领用/报损终审
     * {@code /internal/consumables/deduct}）的既有 {@code {"message":...}} 契约。
     */
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Map<String, Object>> onInsufficientStock(InsufficientStockException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", ex.getReason());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InsufficientStockException.Shortage s : ex.shortages()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("skuCode", s.skuCode());
            row.put("skuName", s.skuName());
            row.put("needQty", s.needQty());
            row.put("stockQty", s.stockQty());
            row.put("unit", s.unit());
            rows.add(row);
        }
        body.put("shortages", rows);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }
}
