package com.meiyun.customer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CustomerService.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(CustomerService.NotFound ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "NOT_FOUND", "message", ex.getMessage()));
    }

    @ExceptionHandler(CustomerService.BadReq.class)
    public ResponseEntity<Map<String, Object>> badReq(CustomerService.BadReq ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "BAD_REQUEST", "message", ex.getMessage()));
    }

    /** 业务参数校验失败（字典必填/唯一冲突等）——统一 400 + 中文原因，不裸 500 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegalArg(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "BAD_REQUEST", "message", String.valueOf(ex.getMessage())));
    }

    /**
     * Bean Validation 失败 → 422 中文。本服务的兜底 Exception handler 会抢先拦截，
     * 故在此显式处理（与 meiyun-security ChineseValidationAdvice 同一中文口径）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
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
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("code", "VALIDATION_FAILED", "message", sb.toString()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception ex) {
        // 原始异常消息可能含英文/堆栈信息，不外露给前端；详情见容器日志。
        log.error("未处理异常，返回兜底 500", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "INTERNAL", "message", "系统繁忙，请稍后重试"));
    }
}
