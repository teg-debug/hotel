package com.hotel.common;

import lombok.Getter;

/**
 * 业务异常：由全局异常处理器统一转换为 Result 返回。
 *
 * <p>只传消息时按「业务规则不满足」处理，对应 422，而不是系统异常 500；
 * 需要更精确的语义（无权限、资源不存在等）时显式传入状态码。</p>
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ResultCode code;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.BUSINESS_ERROR;
    }

    public BusinessException(ResultCode code, String message) {
        super(message);
        this.code = code;
    }
}
