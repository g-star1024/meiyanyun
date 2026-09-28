package com.meiyun.c.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C 端全局异常与包约（铁律 3 中文优先零英文外露）：
 * 成功响应由控制器直接返回 {code:0,message:"ok",data}；
 * 参数校验/路径错误/未知异常统一 {code:HTTP状态码,message:中文,data:null}。
 * 与 mp-uniapp/src/utils/request.ts 包约对齐：code 0/200/undefined 视为成功。
 */
@RestControllerAdvice
public class CWebAdvice {

    private static final Logger log = LoggerFactory.getLogger(CWebAdvice.class);

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<Map<String, Object>> validation(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getDefaultMessage() == null ? "参数校验未通过" : f.getDefaultMessage())
                .orElse("参数校验未通过");
        return build(HttpStatus.BAD_REQUEST, msg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> badBody(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "请求体格式不正确");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> methodNotSupported(HttpRequestMethodNotSupportedException e) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "请求方式不支持");
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoHandlerFoundException e) {
        return build(HttpStatus.NOT_FOUND, "接口不存在");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unknown(Exception e) {
        log.error("C 端未捕获异常（对外统一 500 中文包约，堆栈仅服务端留痕）", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "服务繁忙，请稍后重试");
    }

    private static ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status.value());
        body.put("message", message);
        body.put("data", null);
        return ResponseEntity.status(status).body(body);
    }
}
