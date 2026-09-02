package com.meiyun.marketing;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** 全局异常处理器：把业务异常映射为语义化 HTTP 状态码，避免裸 500。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** ResponseStatusException（业务显式抛出的 4xx，如 404/400/409）→ 透传状态码与中文 reason。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "BUSINESS_ERROR");
        body.put("message", ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString());
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    /** 非法参数 / 业务规则校验失败 → 400。 */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "BAD_REQUEST");
        body.put("message", ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    // Bean Validation 失败（422）由 meiyun-security 的 ChineseValidationAdvice 统一中文化处理，
    // 本服务不再单独处理，避免英文默认消息（must not be blank 等）外露。
}
