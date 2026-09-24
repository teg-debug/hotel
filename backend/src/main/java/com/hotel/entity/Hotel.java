package com.hotel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 酒店表
 */
@Data
@TableName("hotel")
public class Hotel {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 经营者用户ID（后台数据隔离依据） */
    private Long ownerId;
    private String name;
    /** 所在城市（搜索条件） */
    private String city;
    private String address;
    /** 星级：1-5星 */
    private Integer starLevel;
    private String description;
    private String coverImg;
    /** 酒店图片（逗号分隔URL，用于相册放大查看） */
    private String images;
    /** 纬度（地理位置） */
    private BigDecimal latitude;
    /** 经度（地理位置） */
    private BigDecimal longitude;
    private String phone;
    private String checkinTime;
    private String checkoutTime;
    /** 状态：0-下架 1-营业中 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
