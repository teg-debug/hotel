package com.hotel.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 客服工作台 - 会话列表筛选参数
 */
@Data
public class ChatSessionQueryDTO {

    /** 状态：0-进行中 1-已结束 2-已转人工 3-超时回收 */
    private Integer status;

    /** 是否转人工：1-是 0-否 */
    private Integer transferFlag;

    /** 满意度（1-5） */
    private Integer rating;

    /** 开始日期（含） */
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    /** 结束日期（含） */
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    @Min(value = 1, message = "页码最小为1")
    private Integer page = 1;

    @Min(value = 1, message = "每页条数最小为1")
    private Integer size = 10;
}
