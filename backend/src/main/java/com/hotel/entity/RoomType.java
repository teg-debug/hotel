package com.hotel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 房型表
 */
@Data
@TableName("room_type")
public class RoomType {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long hotelId;
    /** 房型名称：大床房/双床房/套房 */
    private String name;
    /** 床型：大床/双床 */
    private String bedType;
    /** 面积（平方米） */
    private Integer area;
    /** 最多入住人数 */
    private Integer maxGuests;
    /** 门市价（元/晚） */
    private BigDecimal price;
    /** 是否含早餐：0-否 1-是 */
    private Integer breakfast;
    private String imgUrl;
    /** 状态：0-停售 1-在售 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
