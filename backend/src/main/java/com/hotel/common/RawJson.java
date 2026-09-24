package com.hotel.common;

import com.fasterxml.jackson.annotation.JsonRawValue;

/**
 * 已序列化 JSON 的透传包装。
 *
 * <p>用于「缓存里存的就是最终 JSON」这类场景：搜索接口的响应体在回源时已经序列化过一次，
 * 命中缓存时不必再把它反序列化成对象、也不必再由 Jackson 走一遍对象图——
 * 直接把 JSON 原样嵌入外层 {@code Result} 即可（外层信封只有几十字节）。</p>
 *
 * <p>之所以不直接把 JSON 字符串当 {@code data} 返回：那样会被序列化成「带转义的字符串」，
 * 客户端拿到的就是 {@code "data": "{\"total\":...}"} 而不是对象。
 * {@link JsonRawValue} 让 Jackson 原样写出这段内容，响应结构对客户端完全不变。</p>
 */
public class RawJson {

    private final String json;

    public RawJson(String json) {
        this.json = json;
    }

    @JsonRawValue
    public String getJson() {
        return json;
    }
}
