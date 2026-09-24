package com.hotel.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 房源搜索请求参数（GET 查询参数绑定）
 */
@Data
public class HotelSearchDTO {

    /** 城市（模糊） */
    private String city;

    /** 星级（1-5） */
    private Integer starLevel;

    /** 关键词（匹配酒店名/地址） */
    private String keyword;

    /** 入住日期（用于检查日期冲突） */
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate checkin;

    /** 离店日期 */
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate checkout;

    @Min(value = 1, message = "页码最小为1")
    private Integer page = 1;

    @Min(value = 1, message = "每页条数最小为1")
    @Max(value = 100, message = "每页条数最大为100")
    private Integer size = 10;
}
