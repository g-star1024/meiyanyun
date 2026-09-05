package com.meiyun.store.consumable;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 耗材域服务（B5）：档案建档、入库（PURCHASE 移动平均重算）、出库（领用 USE / 报损 SCRAP）。
 *
 * <p>出库统一走 {@link #deduct}（由 txn 审批中心终审后以系统身份回调）：
 * 逐 SKU 悲观行锁（SELECT ... FOR UPDATE）防并发超扣；库存不足 422 中文拦截；
 * 幂等键 = 业务单号（审批待办号 AP...）+ SKU，网络重试/补偿重放不双扣。
 * 金额一律 Long 分；出库单价定格为当时移动平均成本价，写流水供成本侧镜像聚合。
 */
@Service
public class ConsumableService {

    private final ConsumableRepository consumableRepo;
    private final ConsumableStockRepository stockRepo;
    private final ConsumableMovementRepository movementRepo;
    private final ConsumableAuditRecorder audit;

    public ConsumableService(ConsumableRepository consumableRepo,
                             ConsumableStockRepository stockRepo,
                             ConsumableMovementRepository movementRepo,
                             ConsumableAuditRecorder audit) {
        this.consumableRepo = consumableRepo;
        this.stockRepo = stockRepo;
        this.movementRepo = movementRepo;
        this.audit = audit;
    }

    /** 400 参数错误（中文） */
    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    /** 422 业务冲突（库存不足/状态不符等，中文） */
    static ResponseStatusException unprocessable(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    /** 建档：一店一 SKU 唯一校验，初始库存行随行落库（initialQty 可为 0）。 */
    @Transactional
    public Consumable createSku(String storeCode, String skuCode, String name, String category,
                                String spec, String unit, long costPriceFen, int safetyStock,
                                int initialQty, String supplier, String location, String operator) {
        if (isBlank(storeCode) || isBlank(skuCode) || isBlank(name) || isBlank(category) || isBlank(unit)) {
            throw badReq("门店、SKU 编码、名称、分类、单位均不能为空");
        }
        if (costPriceFen < 0 || safetyStock < 0 || initialQty < 0) {
            throw badReq("成本价、安全库存、初始库存不能为负");
        }
        if (consumableRepo.findByStoreCodeAndSkuCode(storeCode, skuCode).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "本店已存在 SKU「" + skuCode + "」，请勿重复建档");
        }
        Consumable c = new Consumable();
        c.setStoreCode(storeCode);
        c.setSkuCode(skuCode.trim());
        c.setName(name.trim());
        c.setCategory(category.trim());
        c.setSpec(spec);
        c.setUnit(unit.trim());
        c.setCostPrice(costPriceFen);
        c.setSafetyStock(safetyStock);
        c.setSupplier(supplier);
        c.setLocation(location);
        if (initialQty > 0) c.setLastInAt(OffsetDateTime.now());
        consumableRepo.save(c);

        ConsumableStock stock = new ConsumableStock();
        stock.setConsumableId(c.getId());
        stock.setQty(initialQty);
        stockRepo.save(stock);

        if (initialQty > 0) {
            writeMovement(c, initialQty, "PURCHASE", costPriceFen, "INIT-" + c.getId(),
                    operator, "建档初始入库");
        }
        // audit payload 必须是合法 JSON（audit_log.payload 为 jsonb 列，纯文本会被 PG 拒绝）
        audit.record("CONSUMABLE", "SKU-" + c.getId(), operator, "耗材建档",
                String.format("{\"storeCode\":%s,\"skuCode\":%s,\"name\":%s,\"category\":%s,"
                                + "\"costPriceFen\":%d,\"initialQty\":%d,\"unit\":%s}",
                        jsonStr(c.getStoreCode()), jsonStr(c.getSkuCode()), jsonStr(c.getName()),
                        jsonStr(c.getCategory()), costPriceFen, initialQty, jsonStr(c.getUnit())));
        return c;
    }

    /**
     * 入库（PURCHASE）：行锁库存，移动平均成本价重算
     * {@code newAvg=(oldQty*oldAvg+inQty*inCost)/(oldQty+inQty)}；
     * batchNo 为幂等键（同批次+同 SKU 重放不重复入库）。
     */
    @Transactional
    public void stockIn(String storeCode, String skuCode, int qty, long unitCostFen,
                        String batchNo, String operator, String remark) {
        if (qty <= 0) throw badReq("入库数量必须大于 0");
        if (unitCostFen < 0) throw badReq("入库单价不能为负");
        if (isBlank(batchNo)) throw badReq("入库批次号不能为空（幂等键）");
        Consumable c = mustFind(storeCode, skuCode);
        if (movementRepo.findFirstByBizRefAndConsumableId(batchNo.trim(), c.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "入库批次「" + batchNo + "」已处理，请勿重复提交");
        }
        ConsumableStock stock = stockRepo.lockByConsumableId(c.getId())
                .orElseGet(() -> {
                    ConsumableStock s = new ConsumableStock();
                    s.setConsumableId(c.getId());
                    s.setQty(0);
                    return stockRepo.save(s);
                });
        int oldQty = stock.getQty();
        long oldAvg = c.getCostPrice();
        long newAvg = oldQty + qty == 0 ? unitCostFen
                : (oldQty * oldAvg + qty * unitCostFen) / (oldQty + qty);
        stock.setQty(oldQty + qty);
        stockRepo.save(stock);
        c.setCostPrice(newAvg);
        c.setLastInAt(OffsetDateTime.now());
        consumableRepo.save(c);
        writeMovement(c, qty, "PURCHASE", unitCostFen, batchNo.trim(), operator, remark);
        audit.record("CONSUMABLE", batchNo.trim(), operator, "耗材入库",
                String.format("{\"storeCode\":%s,\"skuCode\":%s,\"qty\":%d,\"unitCostFen\":%d,"
                                + "\"newAvgFen\":%d,\"batchNo\":%s}",
                        jsonStr(c.getStoreCode()), jsonStr(c.getSkuCode()), qty, unitCostFen,
                        newAvg, jsonStr(batchNo.trim())));
    }

    /**
     * 出库（审批中心终审回调，系统身份）：moveType=USE 领用 / SCRAP 报损。
     * 逐 SKU 行锁扣减；bizRef（审批待办号）+ SKU 幂等；库存不足 422 中文回滚整批。
     *
     * <p>幂等重放口径：同 bizRef+SKU 已存在出库流水时不双扣，但回返<b>原行定格金额</b>
     * （从流水反查 qtyChange/unitCost）——审批终审「扣库已提交、终审事务回滚后重试」场景下，
     * 调用方仍能拿到与首扣一致的成本额写成本事件，杜绝重试导致成本事件金额变 0。
     *
     * @return 本次出库的全部行结果（含幂等重放回返的原行）
     */
    @Transactional
    public List<DeductLineResult> deduct(String bizRef, String storeCode, List<DeductLine> lines,
                                         String moveType, String operator) {
        if (isBlank(bizRef)) throw badReq("业务单号不能为空（幂等键）");
        if (isBlank(storeCode)) throw badReq("门店编码不能为空");
        if (lines == null || lines.isEmpty()) throw badReq("出库明细不能为空");
        if (!"USE".equals(moveType) && !"SCRAP".equals(moveType)) {
            throw badReq("出库类型仅支持 USE（领用）/ SCRAP（报损）");
        }
        List<DeductLineResult> results = new ArrayList<>();
        for (DeductLine line : lines) {
            if (line == null || isBlank(line.skuCode()) || line.qty() == null || line.qty() <= 0) {
                throw badReq("出库明细行存在空 SKU 或非法数量");
            }
            Consumable c = mustFind(storeCode, line.skuCode().trim());
            ConsumableMovement existed = movementRepo.findFirstByBizRefAndConsumableId(bizRef, c.getId())
                    .orElse(null);
            if (existed != null) {
                // 幂等重放：不双扣，回返原行定格金额（数量取绝对值，负号为出库方向）
                int deductedQty = Math.abs(existed.getQtyChange());
                results.add(new DeductLineResult(c.getSkuCode(), c.getName(), deductedQty,
                        existed.getUnitCost(), (long) deductedQty * existed.getUnitCost(),
                        stockRepo.findByConsumableId(c.getId()).map(ConsumableStock::getQty).orElse(0),
                        existed.getId()));
                continue;
            }
            ConsumableStock stock = stockRepo.lockByConsumableId(c.getId())
                    .orElseThrow(() -> unprocessable("耗材「" + c.getName() + "」无库存记录，无法出库"));
            if (stock.getQty() < line.qty()) {
                throw unprocessable("耗材「" + c.getName() + "」库存不足：当前" + stock.getQty()
                        + c.getUnit() + "，申请" + line.qty() + c.getUnit());
            }
            long unitCost = c.getCostPrice();
            stock.setQty(stock.getQty() - line.qty());
            stockRepo.save(stock);
            ConsumableMovement m = writeMovement(c, -line.qty(), moveType, unitCost, bizRef,
                    operator, line.remark());
            results.add(new DeductLineResult(c.getSkuCode(), c.getName(), line.qty(), unitCost,
                    line.qty() * unitCost, stock.getQty(), m.getId()));
        }
        long totalFen = results.stream().mapToLong(DeductLineResult::amountFen).sum();
        audit.record("USE".equals(moveType) ? "REQUISITION" : "LOSS_REPORT", bizRef, operator,
                "USE".equals(moveType) ? "领用出库扣减" : "报损出库扣减",
                String.format("{\"storeCode\":%s,\"moveType\":%s,\"skuCount\":%d,"
                                + "\"totalQty\":%d,\"totalCostFen\":%d}",
                        jsonStr(storeCode), jsonStr(moveType), results.size(),
                        results.stream().mapToInt(DeductLineResult::qty).sum(), totalFen));
        return results;
    }

    /** 档案+库存联合视图（金额换算为元）。storeCode 为空取全量（数据域由调用方过滤）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listConsumables(String storeCode, String category, String keyword) {
        String sc = isBlank(storeCode) ? null : storeCode.trim();
        String cat = isBlank(category) ? null : category.trim();
        String kw = isBlank(keyword) ? null : "%" + keyword.trim() + "%";
        List<Consumable> list = consumableRepo.search(sc, cat, kw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Consumable c : list) {
            int qty = stockRepo.findByConsumableId(c.getId()).map(ConsumableStock::getQty).orElse(0);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("storeCode", c.getStoreCode());
            row.put("skuCode", c.getSkuCode());
            row.put("name", c.getName());
            row.put("category", c.getCategory());
            row.put("spec", c.getSpec());
            row.put("unit", c.getUnit());
            row.put("qty", qty);
            row.put("safetyStock", c.getSafetyStock());
            row.put("lowStock", qty <= c.getSafetyStock());
            row.put("supplier", c.getSupplier());
            row.put("location", c.getLocation());
            row.put("avgCostYuan", fenToYuan(c.getCostPrice()));
            row.put("stockValueYuan", fenToYuan((long) qty * c.getCostPrice()));
            row.put("lastInAt", c.getLastInAt());
            out.add(row);
        }
        return out;
    }

    /** 出入库流水视图（金额换算为元）。types 为空默认 PURCHASE/USE/SCRAP。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listMovements(String storeCode, List<String> types) {
        String sc = isBlank(storeCode) ? null : storeCode.trim();
        List<String> useTypes = (types == null || types.isEmpty())
                ? List.of("PURCHASE", "USE", "SCRAP") : types;
        List<ConsumableMovement> movements = movementRepo.searchMovements(sc, useTypes);
        Map<Long, Consumable> skuMap = new LinkedHashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConsumableMovement m : movements) {
            Consumable c = skuMap.computeIfAbsent(m.getConsumableId(),
                    id -> consumableRepo.findById(id).orElse(null));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("storeCode", m.getStoreCode());
            row.put("consumableId", m.getConsumableId());
            row.put("skuCode", c == null ? null : c.getSkuCode());
            row.put("name", c == null ? ("已删除耗材#" + m.getConsumableId()) : c.getName());
            row.put("unit", c == null ? null : c.getUnit());
            row.put("qtyChange", m.getQtyChange());
            row.put("moveType", m.getMoveType());
            row.put("unitCostYuan", fenToYuan(m.getUnitCost()));
            row.put("amountYuan", fenToYuan((long) Math.abs(m.getQtyChange()) * m.getUnitCost()));
            row.put("bizRef", m.getBizRef());
            row.put("operator", m.getOperator());
            row.put("remark", m.getRemark());
            row.put("createdAt", m.getCreatedAt());
            out.add(row);
        }
        return out;
    }

    // ---- 内部辅助 ----

    private Consumable mustFind(String storeCode, String skuCode) {
        return consumableRepo.findByStoreCodeAndSkuCode(storeCode, skuCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "本店不存在 SKU「" + skuCode + "」，请先建档"));
    }

    private ConsumableMovement writeMovement(Consumable c, int qtyChange, String moveType,
                                             long unitCost, String bizRef, String operator, String remark) {
        ConsumableMovement m = new ConsumableMovement();
        m.setConsumableId(c.getId());
        m.setStoreCode(c.getStoreCode());
        m.setQtyChange(qtyChange);
        m.setMoveType(moveType);
        m.setUnitCost(unitCost);
        m.setBizRef(bizRef);
        m.setOperator(operator == null || operator.isBlank() ? "系统" : operator);
        m.setRemark(remark);
        return movementRepo.save(m);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** JSON 字符串转义（审计 payload 手工拼 JSON 时用，与 txn ApprovalService.jsonStr 同口径） */
    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 分 → 元（保留两位） */
    private static double fenToYuan(long fen) {
        return Math.round(fen) / 100.0;
    }

    /** 出库明细行入参 */
    public record DeductLine(String skuCode, Integer qty, String remark) {}

    /** 出库扣减结果（幂等跳过重放时不返回） */
    public record DeductLineResult(String skuCode, String name, int qty, long unitCostFen,
                                   long amountFen, int stockAfter, long movementId) {}
}
