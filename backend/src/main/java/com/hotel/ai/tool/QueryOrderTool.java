package com.hotel.ai.tool;

import com.hotel.common.PageResult;
import com.hotel.dto.OrderQueryDTO;
import com.hotel.security.UserContext;
import com.hotel.service.OrderService;
import com.hotel.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 工具：查询当前用户订单状态（复用 OrderService，数据隔离在当前登录用户维度）
 */
@Component
@RequiredArgsConstructor
public class QueryOrderTool {

    private static final String[] STATUS_TEXT = {"待支付", "已确认", "已入住", "已取消", "已完成"};

    private final OrderService orderService;

    @Tool(description = "查询当前登录用户的预订订单列表与状态。可按订单状态筛选：0待支付 1已确认 2已入住 3已取消 4已完成。")
    public String queryOrder(QueryOrderRequest request, ToolContext toolContext) {
        // 用户身份优先取 ToolContext：流式调用时工具可能运行在响应式线程上，ThreadLocal 为空
        Long userId = ToolUserContext.userId(toolContext);
        if (userId == null) {
            return "缺少登录用户信息，无法查询订单";
        }
        OrderQueryDTO dto = new OrderQueryDTO();
        if (request.status() != null) {
            dto.setStatus(request.status());
        }
        dto.setPage(1);
        dto.setSize(10);
        PageResult<OrderVO> page = UserContext.runAs(userId, null, ToolUserContext.role(toolContext),
                () -> orderService.pageMyOrders(dto));
        if (page.getRecords().isEmpty()) {
            return "您当前没有相关订单";
        }
        StringBuilder sb = new StringBuilder("您的订单如下：\n");
        for (OrderVO o : page.getRecords()) {
            int status = o.getStatus() == null ? 0 : o.getStatus();
            sb.append("· 订单号 ").append(o.getOrderNo())
              .append("，「").append(o.getHotelName()).append("」")
              .append(o.getRoomTypeName())
              .append("，").append(o.getCheckinDate()).append(" 至 ").append(o.getCheckoutDate())
              .append("，").append(o.getTotalAmount()).append(" 元")
              .append("，状态：").append(statusText(status));
            if (status == 3 && o.getCancelReason() != null) {
                sb.append("（").append(o.getCancelReason()).append("）");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String statusText(int status) {
        return status >= 0 && status < STATUS_TEXT.length ? STATUS_TEXT[status] : "未知";
    }

    public record QueryOrderRequest(
            @ToolParam(description = "订单状态筛选，可选：0待支付 1已确认 2已入住 3已取消 4已完成，不传则查询全部", required = false) Integer status) {
    }
}
