package com.hotel.vo;

import com.hotel.entity.BookingOrder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单详情 VO。
 *
 * <p>相比列表用的 {@link OrderVO}，这里补齐了折扣率、备注、支付时间与支付流水，
 * 供详情页完整展示一笔订单，不需要前端再多发几次请求。</p>
 */
@Data
public class OrderDetailVO {

    private String orderNo;
    private Long hotelId;
    private String hotelName;
    private Long roomTypeId;
    private String roomTypeName;
    private Long roomId;
    private String roomNo;
    private LocalDate checkinDate;
    private LocalDate checkoutDate;
    private Integer nightCount;
    private String guestName;
    private String guestPhone;
    private String remark;
    /** 成交房单价（含会员折扣） */
    private BigDecimal roomPrice;
    /** 下单时的会员折扣率，1.00 表示无折扣 */
    private BigDecimal memberDiscount;
    private BigDecimal totalAmount;
    /** 状态：0-待支付 1-已确认 2-已入住 3-已取消 4-已完成 */
    private Integer status;
    private String cancelReason;
    private LocalDateTime payTime;
    /** 待支付订单的超时自动取消时间 */
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 支付与退款流水，按发生顺序正序 */
    private List<PaymentLogVO> payments;

    public static OrderDetailVO from(BookingOrder order, List<PaymentLogVO> payments) {
        OrderDetailVO vo = new OrderDetailVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setHotelId(order.getHotelId());
        vo.setHotelName(order.getHotelName());
        vo.setRoomTypeId(order.getRoomTypeId());
        vo.setRoomTypeName(order.getRoomTypeName());
        vo.setRoomId(order.getRoomId());
        vo.setRoomNo(order.getRoomNo());
        vo.setCheckinDate(order.getCheckinDate());
        vo.setCheckoutDate(order.getCheckoutDate());
        vo.setNightCount(order.getNightCount());
        vo.setGuestName(order.getGuestName());
        vo.setGuestPhone(order.getGuestPhone());
        vo.setRemark(order.getRemark());
        vo.setRoomPrice(order.getRoomPrice());
        vo.setMemberDiscount(order.getMemberDiscount());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setStatus(order.getStatus());
        vo.setCancelReason(order.getCancelReason());
        vo.setPayTime(order.getPayTime());
        vo.setExpireTime(order.getExpireTime());
        vo.setCreateTime(order.getCreateTime());
        vo.setUpdateTime(order.getUpdateTime());
        vo.setPayments(payments == null ? List.of() : payments);
        return vo;
    }
}
