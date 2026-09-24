package com.hotel.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 支付回调签名工具：按参数名升序拼接为 {@code k=v&k=v} 后做 HMAC-SHA256，输出小写十六进制。
 *
 * <p>签名串的拼接规则必须与网关侧保持一致；接入真实网关时以其文档定义为准，
 * 若字段名或排序规则不同，只需调整 {@link #canonical(Map)} 的实现。</p>
 */
public final class PaymentSignUtil {

    private static final String ALGORITHM = "HmacSHA256";

    private PaymentSignUtil() {
    }

    /** 计算签名 */
    public static String sign(Map<String, String> params, String secret) {
        return hmacSha256(canonical(params), secret);
    }

    /** 校验签名，比较过程使用常量时间实现，避免时序侧信道 */
    public static boolean verify(Map<String, String> params, String expectedSign, String secret) {
        if (expectedSign == null || expectedSign.isBlank()) {
            return false;
        }
        byte[] actual = sign(params, secret).getBytes(StandardCharsets.UTF_8);
        byte[] expected = expectedSign.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(actual, expected);
    }

    /** 拼接待签名字符串：参数名升序，空值跳过 */
    public static String canonical(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(params).forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(key).append('=').append(value);
        });
        return sb.toString();
    }

    private static String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("支付签名计算失败", e);
        }
    }
}
