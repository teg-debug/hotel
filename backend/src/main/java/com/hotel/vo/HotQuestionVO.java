package com.hotel.vo;

import lombok.Data;

/**
 * 高频问题统计项。
 *
 * <p>统计口径是<strong>用户真实提问</strong>（按提问原文分组），而不是知识条目的命中次数：
 * 命中次数只能反映「已经存在的知识被用了几次」，无法暴露知识库的缺口，
 * 而 {@code transferCount} 偏高的问题正是需要补充知识的信号。</p>
 */
@Data
public class HotQuestionVO {

    /** 用户提问原文 */
    private String question;
    /** 识别出的意图标签（可能为空） */
    private String category;
    /** 该问题被提问的次数 */
    private Integer askCount;
    /** 其中最终转人工的次数 */
    private Integer transferCount;
}
