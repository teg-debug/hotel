package com.hotel.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量文本切词：为本地哈希嵌入与关键词兜底提供统一的分词口径。
 *
 * <p>关键是区分中英文的处理方式。中文没有词间空格，若只按「非字母数字」切分，
 * 一整句中文会变成一个词元，哈希后落到单一维度上，向量完全失去区分能力。
 * 这里把 CJK 字符逐个切成单字，并额外生成相邻二元组以保留局部顺序信息；
 * 拉丁字母与数字仍按连续串处理。</p>
 */
public final class TextTokenizer {

    private TextTokenizer() {
    }

    /** 切词：返回单字/单词 + 相邻二元组的混合词元集合 */
    public static List<String> tokenize(String text) {
        List<String> units = split(text);
        if (units.size() < 2) {
            return units;
        }
        List<String> tokens = new ArrayList<>(units);
        for (int i = 0; i < units.size() - 1; i++) {
            tokens.add(units.get(i) + units.get(i + 1));
        }
        return tokens;
    }

    /** 基础切分单元：CJK 单字、拉丁词、数字串 */
    private static List<String> split(String text) {
        List<String> units = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return units;
        }
        String lower = text.toLowerCase();
        StringBuilder latin = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                flush(latin, units);
                units.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                latin.append(c);
            } else {
                flush(latin, units);
            }
        }
        flush(latin, units);
        return units;
    }

    private static void flush(StringBuilder latin, List<String> units) {
        if (latin.length() > 0) {
            units.add(latin.toString());
            latin.setLength(0);
        }
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 基本汉字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF); // 兼容汉字
    }
}
