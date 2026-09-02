package com.meiyun.org;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "timestamp", OffsetDateTime.now().toString(),
                "status", ex.getStatusCode().value(),
                "message", ex.getReason() == null ? ex.getMessage() : ex.getReason()));
    }

    /** Bean Validation 失败 → 422 中文（兜底处理器在同 advice 内会抢先，需显式处理）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        StringBuilder sb = new StringBuilder("参数校验未通过：");
        boolean first = true;
        for (var fe : ex.getBindingResult().getFieldErrors()) {
            if (!first) sb.append("；");
            first = false;
            String zh = switch (fe.getCode() == null ? "" : fe.getCode()) {
                case "NotBlank", "NotEmpty" -> "不能为空";
                case "NotNull" -> "不能缺省";
                case "Size" -> "长度不符合要求";
                case "Min", "Max" -> "数值超出允许范围";
                case "Pattern" -> "格式不正确";
                default -> "校验未通过";
            };
            sb.append(fe.getField()).append(zh);
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "timestamp", OffsetDateTime.now().toString(),
                "status", HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "message", sb.toString()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "timestamp", OffsetDateTime.now().toString(),
                "status", 500,
                "message", "系统繁忙，请稍后重试"));
    }
}
