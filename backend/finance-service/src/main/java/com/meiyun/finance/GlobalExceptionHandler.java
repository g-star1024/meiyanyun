package com.meiyun.finance;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** 全局异常处理器：把业务异常映射为语义化 HTTP 状态码与中文消息，避免裸 500。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** ResponseStatusException（业务显式抛出的 4xx，如 400/404/409/422）→ 透传状态码与中文 reason。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "BUSINESS_ERROR");
        body.put("message", ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString());
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    /** 非法参数 / 业务规则校验失败 → 422（资金分录入参语义校验，请求体合法但字段不达标）。 */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> handleUnprocessable(RuntimeException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "UNPROCESSABLE_ENTITY");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    // Bean Validation 失败（422）由 meiyun-security 的 ChineseValidationAdvice 统一中文化处理。
}
