package com.hotel.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 知识条目编辑入参。
 *
 * <p>字段均为可选，只更新传入的部分；所属酒店（决定知识生效范围与维护权限）
 * 不允许通过编辑变更，避免改动权限边界。</p>
 */
@Data
public class KnowledgeUpdateDTO {

    @Size(max = 500, message = "标准问题不能超过 500 个字")
    private String question;

    private String answer;

    @Size(max = 50, message = "分类不能超过 50 个字")
    private String category;

    /** 召回阈值，取值 0.1 ~ 1.0 */
    private BigDecimal similarityThreshold;

    /** 状态：0-停用 1-启用 */
    private Integer status;
}
