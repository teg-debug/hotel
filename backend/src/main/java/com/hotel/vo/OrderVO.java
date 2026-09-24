package com.hotel.vo;

import com.hotel.entity.BookingOrder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单返回 VO
 */
@Data
public class OrderVO {

    private String orderNo;
    private Long hotelId;
    private String hotelName;
    private Long roomTypeId;
    private String roomTypeName;
    private String roomNo;
    private LocalDate checkinDate;
    private LocalDate checkoutDate;
    private Integer nightCount;
    private String guestName;
    private String guestPhone;
    /** 成交房单价（含会员折扣） */
    private BigDecimal roomPrice;
    /** 订单总金额 */
    private BigDecimal totalAmount;
    /** 状态：0-待支付 1-已确认 2-已入住 3-已取消 4-已完成 */
    private Integer status;
    private String cancelReason;
    /** 超时未支付自动取消时间 */
    private LocalDateTime expireTime;
    private LocalDateTime createTime;

    public static OrderVO from(BookingOrder order) {
        OrderVO vo = new OrderVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setHotelId(order.getHotelId());
        vo.setHotelName(order.getHotelName());
        vo.setRoomTypeId(order.getRoomTypeId());
        vo.setRoomTypeName(order.getRoomTypeName());
        vo.setRoomNo(order.getRoomNo());
        vo.setCheckinDate(order.getCheckinDate());
        vo.setCheckoutDate(order.getCheckoutDate());
        vo.setNightCount(order.getNightCount());
        vo.setGuestName(order.getGuestName());
        vo.setGuestPhone(order.getGuestPhone());
        vo.setRoomPrice(order.getRoomPrice());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setStatus(order.getStatus());
        vo.setCancelReason(order.getCancelReason());
        vo.setExpireTime(order.getExpireTime());
        vo.setCreateTime(order.getCreateTime());
        return vo;
    }
}
