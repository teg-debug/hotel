package com.hotel.common;

import lombok.Data;

/**
 * 统一返回结果 Result&lt;T&gt;：code / msg / data
 */
@Data
public class Result<T> {

    private Integer code;
    private String msg;
    private T data;

    public static <T> Result<T> success() {
        return build(ResultCode.SUCCESS, null);
    }

    public static <T> Result<T> success(T data) {
        return build(ResultCode.SUCCESS, data);
    }

    public static <T> Result<T> error(ResultCode code) {
        return build(code, null);
    }

    public static <T> Result<T> error(ResultCode code, String msg) {
        Result<T> result = new Result<>();
        result.setCode(code.getCode());
        result.setMsg(msg);
        return result;
    }

    private static <T> Result<T> build(ResultCode code, T data) {
        Result<T> result = new Result<>();
        result.setCode(code.getCode());
        result.setMsg(code.getMsg());
        result.setData(data);
        return result;
    }
}
