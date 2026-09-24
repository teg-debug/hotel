package com.hotel.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 业务编号生成器。
 *
 * <p>编号 = 前缀 + 毫秒级时间戳(17位) + 随机后缀。
 * 相比原先的「秒级时间戳 + 4 位随机数」（同一秒内仅 1 万种组合），
 * 碰撞概率大幅下降；调用方仍应保留对唯一键冲突的重试。</p>
 */
public final class OrderNoGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 去掉易混淆的 I / O，降低人工核对时的误读率 */
    private static final String ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private static final int DEFAULT_RANDOM_LENGTH = 6;

    private OrderNoGenerator() {
    }

    /** 订单号：H + 时间戳 + 6 位随机后缀 */
    public static String generate() {
        return generate("H", DEFAULT_RANDOM_LENGTH);
    }

    /** 支付流水号：P + 时间戳 + 6 位随机后缀 */
    public static String generatePayNo() {
        return generate("P", DEFAULT_RANDOM_LENGTH);
    }

    /** 退款流水号：R + 时间戳 + 6 位随机后缀 */
    public static String generateRefundNo() {
        return generate("R", DEFAULT_RANDOM_LENGTH);
    }

    /** 通用编号：前缀 + 时间戳 + 指定长度随机后缀 */
    public static String generate(String prefix, int randomLength) {
        return prefix + LocalDateTime.now().format(FORMATTER) + randomSuffix(Math.max(randomLength, 1));
    }

    private static String randomSuffix(int length) {
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
