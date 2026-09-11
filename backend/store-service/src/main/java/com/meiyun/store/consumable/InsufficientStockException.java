package com.meiyun.store.consumable;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 库存不足业务异常（B34）：在 422 中文文案之外<b>额外携带结构化缺料行</b>，
 * 供 BOM 自动扣料内部端点回传给 txn 登记 {@code bom_deduct_exception.detail_json}。
 *
 * <p>向后兼容：本类仍是 {@link ResponseStatusException}(422)，未做局部处理的端点
 * （如领用/报损终审 {@code /internal/consumables/deduct}）行为与原 {@code unprocessable(msg)} 完全一致——
 * Spring 默认错误体只输出 message，缺料行不外溢。
 */
public class InsufficientStockException extends ResponseStatusException {

    private final transient List<Shortage> shortages;

    public InsufficientStockException(String reason, List<Shortage> shortages) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, reason);
        this.shortages = shortages == null ? List.of() : List.copyOf(shortages);
    }

    public List<Shortage> shortages() {
        return shortages;
    }

    /** 缺料行：需求量 needQty > 现存量 stockQty（无库存记录记 0）。 */
    public record Shortage(String skuCode, String skuName, int needQty, int stockQty, String unit) {}
}
