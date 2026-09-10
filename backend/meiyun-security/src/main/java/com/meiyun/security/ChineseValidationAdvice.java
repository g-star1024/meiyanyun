package com.meiyun.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全站统一入参异常处理（铁律：中文错误零英文外露）。
 *
 * <p>各服务 DTO 上的 {@code @NotBlank/@NotNull} 等注解默认消息是英文
 * （must not be blank / must not be null），若直接透传给前端会违反中文铁律。
 * 本 advice 按「注解语义 + 字段名」统一翻译为中文，所有依赖 meiyun-security 的服务自动生效。</p>
 *
 * <p>双轨语义：422 = 请求体语法合法但业务字段校验未过（Bean Validation）；
 * 400 = 请求本身无法被框架正确解析（体缺失/JSON 畸形、缺必填 query 参数、参数类型不匹配）。
 * 响应体统一：{@code {timestamp, status, message}}，message 全中文。</p>
 */
@RestControllerAdvice
public class ChineseValidationAdvice {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handle(MethodArgumentNotValidException ex) {
        Map<String, Object> body = body(HttpStatus.UNPROCESSABLE_ENTITY);
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

    /** 请求体缺失或 JSON 结构畸形（Jackson 解析失败），默认英文 400 → 中文 400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return badRequest("请求体格式错误或缺失，请检查 JSON 结构是否完整");
    }

    /** 缺少必填 query/form 参数（@RequestParam 必填项缺省），默认英文 400 → 中文 400。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return badRequest("缺少必填请求参数：" + ex.getParameterName());
    }

    /** 参数类型不匹配（如分页 page 传非数字），默认英文 400 → 中文 400。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String value = ex.getValue() == null ? "" : String.valueOf(ex.getValue());
        return badRequest("参数 " + ex.getName() + " 格式不正确：" + value);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> body = body(HttpStatus.BAD_REQUEST);
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private Map<String, Object> body(HttpStatus status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        return body;
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
