package com.meiyun.txn;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * BOM 自动扣料被 store 业务拒绝（B34）：在原中文 reason 之外<b>额外携带缺料明细 JSON</b>，
 * 供 {@code BomDeductService.registerFailure} 落 {@code bom_deduct_exception.detail_json}。
 *
 * <p>向后兼容：本类仍是 {@link ResponseStatusException}，状态码与 reason 与改造前完全一致；
 * {@code detailJson} 可为 null（404 SKU 未建档、服务不可用等无行明细场景）。
 */
public class BomShortageException extends ResponseStatusException {

    private final String detailJson;

    public BomShortageException(HttpStatusCode status, String reason, String detailJson) {
        super(status == null ? HttpStatus.UNPROCESSABLE_ENTITY : status, reason);
        this.detailJson = detailJson;
    }

    /** 缺料明细紧凑 JSON：[{skuCode,skuName,needQty,stockQty,unit}]，无明细时为 null。 */
    public String detailJson() {
        return detailJson;
    }
}
