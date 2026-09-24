package com.hotel.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一状态码枚举。
 *
 * <p>这些状态码同时决定响应的 HTTP 状态码（见 {@link GlobalExceptionHandler}），
 * 因此「业务规则不满足」与「系统异常」必须分开：前者是调用方可以修正的，
 * 后者需要运维介入，混用会让前端与监控都失去判断依据。</p>
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未登录或登录已失效"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源状态冲突"),
    BUSINESS_ERROR(422, "业务处理失败"),
    ERROR(500, "系统繁忙，请稍后重试");

    private final int code;
    private final String msg;
}
