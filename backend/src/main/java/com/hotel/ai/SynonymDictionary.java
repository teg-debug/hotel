package com.hotel.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 酒店领域同义词表。
 *
 * <p>本地检索只能做字面匹配，而住客提问与知识库条目表达同一件事时用词常常完全不同
 * （「有停车位吗」与「酒店提供停车场」几乎没有字符重叠），这类问题原先一律召回不到。</p>
 *
 * <p>做法是把每个同义词组映射成一个规范标记：命中间组词的问题与条目会带上同一个标记，
 * 于是在词项层面变成精确匹配，召回率因此提升。只做受控的等价展开，
 * 不做泛化推导，避免把不相关的问题也召回进来。</p>
 */
public final class SynonymDictionary {

    /** 等价词组：同一组内的词被视为同一概念 */
    private static final List<Set<String>> GROUPS = List.of(
            Set.of("停车", "停车场", "车位", "泊车"),
            Set.of("押金", "保证金", "预授权"),
            Set.of("退房", "离店", "退宿", "checkout"),
            Set.of("入住", "入店", "checkin", "到店"),
            Set.of("早餐", "早饭", "自助早餐"),
            Set.of("发票", "开票", "报销凭证"),
            Set.of("宠物", "携宠", "带狗", "带猫"),
            Set.of("取消", "退订", "撤销订单"),
            Set.of("退款", "退钱", "返还"),
            Set.of("加床", "加床费", "加人"),
            Set.of("wifi", "无线网", "无线网络", "上网"),
            Set.of("健身房", "泳池", "游泳池", "康体"),
            Set.of("钟点房", "小时房", "半日房"),
            Set.of("延迟退房", "晚点走", "超时退房"),
            Set.of("接送", "接机", "送机", "班车", "接驳"),
            Set.of("寄存", "行李寄存", "存行李", "寄放"),
            Set.of("儿童", "小孩", "亲子", "婴儿床"),
            Set.of("洗衣", "洗衣服务", "熨烫")
    );

    /** 与 GROUPS 一一对应的规范标记，刻意使用不会与自然语言冲突的形式 */
    private static final List<String> MARKERS = buildMarkers();

    private SynonymDictionary() {
    }

    /** 词组数量：用于诊断与自检 */
    public static int groupCount() {
        return GROUPS.size();
    }

    /**
     * 返回文本命中的同义词组标记。
     *
     * <p>标记会被当作额外词项参与打分，因此「问到停车」与「答到停车场」会共享同一个词项。</p>
     */
    public static List<String> markersOf(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> hit = new ArrayList<>();
        for (int i = 0; i < GROUPS.size(); i++) {
            if (containsAny(lower, GROUPS.get(i))) {
                hit.add(MARKERS.get(i));
            }
        }
        return hit;
    }

    /**
     * 返回命中的同义词组内的全部词，用于扩大关键词召回范围。
     *
     * <p>没有这一步，纯同义替换的问题（「押金多少」对「保证金退还规则」）连候选都进不了，
     * 后续打分再准也无从发挥。</p>
     */
    public static List<String> expandTerms(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> expanded = new LinkedHashSet<>();
        for (Set<String> group : GROUPS) {
            if (containsAny(lower, group)) {
                expanded.addAll(group);
            }
        }
        return new ArrayList<>(expanded);
    }

    /**
     * 把文本扩充为「原文 + 命中的同义词组全部词」，用于向量化。
     *
     * <p>索引侧与查询侧必须用同一套扩充规则，同义表达才会在向量空间里彼此靠近；
     * 只扩充一侧相当于单方面给查询加噪声。</p>
     */
    public static String expandForEmbedding(String text) {
        List<String> extras = expandTerms(text);
        if (extras.isEmpty()) {
            return text;
        }
        return text + " " + String.join(" ", extras);
    }

    private static boolean containsAny(String lowerText, Set<String> words) {
        for (String word : words) {
            if (lowerText.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> buildMarkers() {
        List<String> markers = new ArrayList<>(GROUPS.size());
        for (int i = 0; i < GROUPS.size(); i++) {
            markers.add("#s" + i);
        }
        return List.copyOf(markers);
    }
}
