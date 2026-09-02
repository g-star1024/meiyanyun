package com.meiyun.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全站统一 Bean Validation 异常处理（铁律：中文错误零英文外露）。
 *
 * <p>各服务 DTO 上的 {@code @NotBlank/@NotNull} 等注解默认消息是英文
 * （must not be blank / must not be null），若直接透传给前端会违反中文铁律。
 * 本 advice 按「注解语义 + 字段名」统一翻译为中文，所有依赖 meiyun-security 的服务自动生效。</p>
 *
 * <p>422 语义保留（请求体语法合法但业务字段校验未过）；响应体与鉴权拦截器一致：
 * {@code {timestamp, status, message, fields[]}}，message 为中文汇总。</p>
 */
@RestControllerAdvice
public class ChineseValidationAdvice {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handle(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", HttpStatus.UNPROCESSABLE_ENTITY.value());

        StringBuilder sb = new StringBuilder("参数校验未通过：");
        boolean first = true;
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            if (!first) sb.append("；");
            first = false;
            sb.append(translate(fe));
        }
        body.put("message", sb.toString());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    /** 按校验注解类型翻译成中文，字段名（英文键名）保留供前端定位字段。 */
    private String translate(FieldError fe) {
        String field = fe.getField();
        String code = fe.getCode();
        String zh = switch (code == null ? "" : code) {
            case "NotBlank", "NotEmpty" -> "不能为空";
            case "NotNull" -> "不能缺省";
            case "Size" -> "长度不符合要求";
            case "Min", "Max" -> "数值超出允许范围";
            case "Pattern" -> "格式不正确";
            case "Email" -> "邮箱格式不正确";
            case "Positive", "PositiveOrZero" -> "必须为正数";
            default -> "校验未通过";
        };
        return field + zh;
    }
}
