package com.hotel.ai.tool;

import com.hotel.common.BusinessException;
import com.hotel.dto.CreateOrderDTO;
import com.hotel.security.UserContext;
import com.hotel.service.BookingService;
import com.hotel.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 工具：创建预订订单（复用 BookingService 的 Redisson 分布式锁防超卖 + 事务）
 * <p>安全说明：工具只负责参数透传，订单归属取当前登录用户（ToolContext 优先，其次 UserContext），
 * AI 无法代他人下单。
 */
@Component
@RequiredArgsConstructor
public class CreateOrderTool {

    private final BookingService bookingService;

    @Tool(description = "创建酒店预订订单。需提供酒店ID、房型ID、入住/离店日期(yyyy-MM-dd)、入住人姓名与电话。下单后订单为待支付状态。")
    public String createOrder(CreateOrderRequest request, ToolContext toolContext) {
        if (request.guestName() == null || request.guestName().isBlank()
                || request.guestPhone() == null || request.guestPhone().isBlank()) {
            return "请提供入住人姓名和联系电话后再下单";
        }
        LocalDate checkin = parse(request.checkinDate());
        LocalDate checkout = parse(request.checkoutDate());
        if (checkin == null || checkout == null) {
            return "日期格式不正确，请提供 yyyy-MM-dd 格式的入住和离店日期";
        }
        if (request.hotelId() == null || request.hotelId() <= 0
                || request.roomTypeId() == null || request.roomTypeId() <= 0) {
            return "缺少酒店或房型信息，请先确认预订哪个酒店房型";
        }
        // 用户身份优先取 ToolContext：流式调用时工具可能运行在响应式线程上，ThreadLocal 为空
        Long userId = ToolUserContext.userId(toolContext);
        if (userId == null) {
            return "缺少登录用户信息，无法下单";
        }

        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setHotelId(request.hotelId());
        dto.setRoomTypeId(request.roomTypeId());
        dto.setCheckinDate(checkin);
        dto.setCheckoutDate(checkout);
        dto.setGuestName(request.guestName().trim());
        dto.setGuestPhone(request.guestPhone().trim());
        try {
            OrderVO order = UserContext.runAs(userId, null, ToolUserContext.role(toolContext),
                    () -> bookingService.createOrder(dto));
            return "下单成功！订单号 " + order.getOrderNo()
                    + "，「" + order.getRoomTypeName() + "」"
                    + order.getCheckinDate() + " 至 " + order.getCheckoutDate()
                    + "，" + order.getNightCount() + " 晚，合计 " + order.getTotalAmount() + " 元。"
                    + "订单状态：待支付，请尽快完成支付，超时将自动取消。";
        } catch (BusinessException e) {
            return "下单失败：" + e.getMessage();
        }
    }

    private LocalDate parse(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public record CreateOrderRequest(
            @ToolParam(description = "酒店ID") Long hotelId,
            @ToolParam(description = "房型ID") Long roomTypeId,
            @ToolParam(description = "入住日期，格式 yyyy-MM-dd") String checkinDate,
            @ToolParam(description = "离店日期，格式 yyyy-MM-dd") String checkoutDate,
            @ToolParam(description = "入住人姓名") String guestName,
            @ToolParam(description = "入住人联系电话") String guestPhone) {
    }
}
