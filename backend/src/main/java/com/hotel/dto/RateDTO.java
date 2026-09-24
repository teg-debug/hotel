package com.hotel.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 满意度评价请求参数
 */
@Data
public class RateDTO {

    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分范围1-5")
    @Max(value = 5, message = "评分范围1-5")
    private Integer rating;

    /** 评价内容（可选） */
    private String comment;
}
