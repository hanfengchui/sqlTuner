package com.codex.sqltuner.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNotFound(NotFoundException e, HttpServletRequest request) {
        log.warn("handleNotFound result 结果: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("NOT_FOUND", e.getMessage(), request, null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        log.warn("handleIllegalArgument result 结果: {}", e.getMessage());
        return ResponseEntity.badRequest().body(error("BAD_REQUEST", e.getMessage(), request, null));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Object>> handleApi(ApiException e, HttpServletRequest request) {
        log.warn("handleApi result 结果: code: {}, message: {}", e.getCode(), e.getMessage());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.getStatus());
        if (e.getStatus() == 429) {
            builder.header("Retry-After", "5");
        }
        return builder.body(error(e.getCode(), e.getMessage(), request, e.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        String message = e.getBindingResult().getAllErrors().isEmpty()
                ? "参数校验失败"
                : e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("handleValidation result 结果: {}", message);
        return ResponseEntity.badRequest().body(error("VALIDATION_FAILED", message, request, null));
    }

    @ExceptionHandler({MissingCsrfTokenException.class, InvalidCsrfTokenException.class})
    public ResponseEntity<ApiResponse<Object>> handleCsrf(Exception e, HttpServletRequest request) {
        log.warn("handleCsrf result 结果: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("CSRF_INVALID", "CSRF Token 无效或缺失", request, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleException(Exception e, HttpServletRequest request) {
        // 异常链可能携带模型原文、用户 SQL 或数据库参数；生产日志只保留请求关联 ID 和异常类型。
        log.error("handleException error 异常: requestId: {}, errorType: {}",
                requestId(request), e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error("INTERNAL_ERROR", "系统异常", request, null));
    }

    private ApiResponse<Object> error(String code, String message, HttpServletRequest request, Object details) {
        return ApiResponse.fail(code, message, requestId(request), details);
    }

    private String requestId(HttpServletRequest request) {
        String requestId = (String) request.getAttribute("requestId");
        return requestId == null ? request.getHeader("X-Request-Id") : requestId;
    }
}
