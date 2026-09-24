package com.hotel.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 意图识别（规则版，零延迟）：
 * 用关键词词典对用户输入分类；出范围（Out-of-Scope）意图直接触发转人工。
 * <p>词典设计成可扩展 Map，关键词命中即归类；可后续替换/升级为 LLM 结构化输出。</p>
 *
 * <p>匹配优先级见 {@link #isBetterMatch}：长词优先 → 等长时先出现者优先 → 词字典序。
 * 最后一层用于摆脱对 {@code Map} 迭代顺序的依赖，保证同一句话在任何 JVM 上都得到同一结论。</p>
 */
@Component
public class IntentClassifier {

    /** 意图常量 */
    public static final String INTENT_BOOKING = "预订";
    public static final String INTENT_QUERY_ROOM = "查房态";
    public static final String INTENT_QUERY_ORDER = "查订单";
    public static final String INTENT_SERVICE = "服务请求";
    public static final String INTENT_HOTEL_INFO = "酒店信息";
    public static final String INTENT_FACILITY = "服务设施";
    public static final String INTENT_NEARBY = "周边推荐";
    public static final String INTENT_CHITCHAT = "闲聊";
    public static final String INTENT_GENERAL = "一般咨询";
    public static final String INTENT_OUT_OF_SCOPE = "出范围";

    /** 出范围意图：支付纠纷/退款/投诉/隐私/医疗/法律等 → 一律转人工 */
    private static final List<String> OUT_OF_SCOPE_KEYWORDS = List.of(
            "退款", "退钱", "赔偿", "投诉", "举报", "差评", "维权",
            // 隐私类：除了固定说法，还要覆盖「泄露了我的个人信息」这类语序倒装
            "隐私", "个人信息", "个人信息泄露", "泄露",
            "法律", "律师", "起诉",
            "医疗", "看病", "药", "急诊", "自杀", "报警", "警察", "威胁", "赔偿金"
    );

    private static final Map<String, List<String>> INTENT_KEYWORDS = Map.of(
            INTENT_BOOKING, List.of("订", "预订", "预定", "订房", "下单", "订一间", "预约", "开房", "帮我订"),
            INTENT_QUERY_ROOM, List.of("有没有房", "空房", "房型", "几间", "房价", "多少钱", "什么价", "价格", "还有房", "查房"),
            // 「订单」单独成词：覆盖「订单能取消吗」这类不含固定短语的问法
            INTENT_QUERY_ORDER, List.of("订单", "我的订单", "查订单", "订单状态", "订单到哪", "订单号", "订单列表"),
            INTENT_SERVICE, List.of("送水", "矿泉水", "热水", "六小件", "洗漱", "牙具", "拖鞋", "清洁", "打扫", "叫早", "唤醒", "行李", "加被", "枕头", "洗衣", "客房服务"),
            // 入住/退房时间的多种问法：原先只有「几点入住」这一连续写法，漏了「几点可以入住」等
            INTENT_HOTEL_INFO, List.of("电话", "地址", "在哪", "怎么走", "入住时间", "退房时间",
                    "几点入住", "几点退房", "几点可以入住", "几点能入住", "入住是几点", "什么时候入住",
                    "几点可以退房", "几点能退房", "退房是几点", "什么时候退房",
                    "政策", "房间", "酒店介绍", "营业"),
            // 宠物类：与 SynonymDictionary 的「宠物」词组保持一致
            INTENT_FACILITY, List.of("停车场", "车位", "餐厅", "健身房", "wifi", "wi-fi", "无线", "发票", "开票", "早餐", "游泳池", "spa",
                    "宠物", "带宠物", "携宠", "带猫", "带狗"),
            INTENT_NEARBY, List.of("天气", "交通", "机场", "地铁", "公交", "景点", "美食", "餐厅推荐", "附近", "周边", "车站"),
            INTENT_CHITCHAT, List.of("你好", "您好", "hi", "hello", "哈喽", "在吗", "谢谢", "你是谁", "能干嘛", "再见")
    );

    private static final Pattern OUT_OF_SCOPE_PATTERN =
            Pattern.compile("(" + String.join("|", OUT_OF_SCOPE_KEYWORDS.stream().map(Pattern::quote).toList()) + ")");

    /** 平铺后的关键词表：匹配顺序只由规则决定，与 Map 的迭代顺序无关 */
    private static final List<Keyword> KEYWORDS = flatten();

    private record Keyword(String intent, String word) {
    }

    private static List<Keyword> flatten() {
        List<Keyword> keywords = new ArrayList<>();
        INTENT_KEYWORDS.forEach((intent, words) -> words.forEach(
                word -> keywords.add(new Keyword(intent, word.toLowerCase(Locale.ROOT)))));
        return List.copyOf(keywords);
    }

    /**
     * 匹配优先级：长词优先（「订单状态」压过「订」）→ 等长时先出现者优先
     * （「附近有餐厅吗」按「附近」归类，而不是「餐厅」）→ 仍相同则按词字典序。
     *
     * <p>第三层是为了摆脱对 {@code Map} 迭代顺序的依赖：原先两个等长关键词分属不同意图时，
     * 命中哪个取决于 {@code Map.of} 的迭代顺序，同一句话在不同 JVM 上可能给出不同结论。</p>
     */
    private static boolean isBetterMatch(String word, int index, String bestWord, int bestIndex) {
        if (bestWord == null) {
            return true;
        }
        if (word.length() != bestWord.length()) {
            return word.length() > bestWord.length();
        }
        if (index != bestIndex) {
            return index < bestIndex;
        }
        return word.compareTo(bestWord) < 0;
    }

    /** 识别结果 */
    public record IntentResult(String intent, boolean outOfScope, String reason) {
    }

    /**
     * 分类用户输入：先判出范围，再按词典归类，最后默认一般咨询
     */
    public IntentResult classify(String text) {
        if (text == null || text.isBlank()) {
            return new IntentResult(INTENT_GENERAL, false, null);
        }
        String lower = text.toLowerCase();

        // 1. 出范围检测（优先级最高）
        if (OUT_OF_SCOPE_PATTERN.matcher(lower).find()) {
            return new IntentResult(INTENT_OUT_OF_SCOPE, true, "检测到退款/投诉/隐私等出范围话题，已转人工处理");
        }
        // 2. 用户明确要求转人工
        if (lower.contains("人工") || lower.contains("客服") || lower.contains("真人") || lower.contains("转接")) {
            return new IntentResult(INTENT_OUT_OF_SCOPE, true, "用户要求转接人工客服");
        }

        // 3. 词典意图匹配：长词优先 → 等长时先出现者优先 → 词字典序（与 Map 迭代顺序无关）
        String best = INTENT_GENERAL;
        String bestWord = null;
        int bestIndex = 0;
        for (Keyword keyword : KEYWORDS) {
            int index = lower.indexOf(keyword.word());
            if (index < 0 || !isBetterMatch(keyword.word(), index, bestWord, bestIndex)) {
                continue;
            }
            best = keyword.intent();
            bestWord = keyword.word();
            bestIndex = index;
        }
        return new IntentResult(best, false, null);
    }

    /** 是否属于"知识库类"意图（酒店信息/设施/周边/政策 → 走 RAG 检索） */
    public static boolean isKnowledgeIntent(String intent) {
        return INTENT_HOTEL_INFO.equals(intent) || INTENT_FACILITY.equals(intent)
                || INTENT_NEARBY.equals(intent);
    }
}
