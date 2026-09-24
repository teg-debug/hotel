package com.hotel.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器：统一转换为 Result&lt;T&gt;，并让 HTTP 状态码与错误语义一致。
 *
 * <p>此前所有异常都返回 HTTP 200，只用响应体里的 code 区分成败，
 * 导致前端必须维护两套判断逻辑，监控与网关也无法按状态码识别失败。
 * 现在按错误语义映射状态码，响应体结构保持不变。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：按状态码映射 HTTP 状态 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        return ResponseEntity.status(httpStatusOf(e.getCode()))
                .body(Result.error(e.getCode(), e.getMessage()));
    }

    /** @RequestBody 参数校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return ResponseEntity.badRequest().body(Result.error(ResultCode.PARAM_ERROR, msg));
    }

    /** 查询参数（@ModelAttribute）绑定/校验失败 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return ResponseEntity.badRequest().body(Result.error(ResultCode.PARAM_ERROR, msg));
    }

    /** 唯一键冲突：多为重复提交或编号碰撞，属于调用方可重试的冲突 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一键冲突", e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.error(ResultCode.CONFLICT, "数据已存在或状态冲突，请刷新后重试"));
    }

    /** 请求体无法解析：属于调用方参数问题，不应算作服务端异常 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Result.error(ResultCode.PARAM_ERROR, "请求体格式不正确"));
    }

    /** 兜底异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(ResultCode.ERROR));
    }

    private HttpStatus httpStatusOf(ResultCode code) {
        return switch (code) {
            case SUCCESS -> HttpStatus.OK;
            case PARAM_ERROR -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case BUSINESS_ERROR -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
