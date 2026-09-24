package com.hotel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 预订订单表
 */
@Data
@TableName("booking_order")
public class BookingOrder {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 订单编号（业务唯一） */
    private String orderNo;
    private Long userId;
    /** 酒店ID/名称快照 */
    private Long hotelId;
    private String hotelName;
    /** 房型ID/名称快照 */
    private Long roomTypeId;
    private String roomTypeName;
    /** 锁定房间ID/房间号快照 */
    private Long roomId;
    private String roomNo;
    private LocalDate checkinDate;
    private LocalDate checkoutDate;
    /** 入住晚数 */
    private Integer nightCount;
    private String guestName;
    private String guestPhone;
    /** 成交房单价（含会员折扣） */
    private BigDecimal roomPrice;
    /** 下单时会员折扣率 */
    private BigDecimal memberDiscount;
    /** 订单总金额 */
    private BigDecimal totalAmount;
    /** 状态：0-待支付 1-已确认 2-已入住 3-已取消 4-已完成 */
    private Integer status;
    private String cancelReason;
    private String remark;
    private LocalDateTime payTime;
    /** 过期时间（超时未支付自动取消） */
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
