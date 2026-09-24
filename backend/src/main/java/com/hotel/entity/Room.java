package com.hotel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 房间表（预订的原子单位）。
 *
 * <p>{@code status} 只表示房间的物理/运营状态，不代表库存占用：
 * 「某天是否已被预订」由 {@code booking_order} 的日期区间决定。
 * 把「已订」写进状态会让房间在被订过任意一天后于所有日期停售。</p>
 */
@Data
@TableName("room")
public class Room {

    /** 空闲可售 */
    public static final int STATUS_IDLE = 0;
    /** 停用维修（不可售） */
    public static final int STATUS_DISABLED = 1;
    /** 打扫中（不可售） */
    public static final int STATUS_CLEANING = 2;

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属酒店ID */
    private Long hotelId;
    /** 所属房型ID */
    private Long roomTypeId;
    /** 房间号（如801） */
    private String roomNo;
    private Integer floor;
    /** 状态：0-空闲 1-停用维修 2-打扫中 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
