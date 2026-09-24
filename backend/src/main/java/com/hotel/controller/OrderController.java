package com.hotel.controller;

import com.hotel.common.PageResult;
import com.hotel.common.Result;
import com.hotel.dto.OrderQueryDTO;
import com.hotel.service.OrderService;
import com.hotel.vo.OrderDetailVO;
import com.hotel.vo.OrderVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端订单：查询 / 详情 / 取消 / 模拟支付
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    public Result<PageResult<OrderVO>> page(@Valid OrderQueryDTO dto) {
        return Result.success(orderService.pageMyOrders(dto));
    }

    /** 订单详情：仅限本人订单，非本人返回 403 */
    @GetMapping("/{orderNo}")
    public Result<OrderDetailVO> detail(@PathVariable String orderNo) {
        return Result.success(orderService.getOrderDetail(orderNo));
    }

    @PostMapping("/{orderNo}/cancel")
    public Result<OrderVO> cancel(@PathVariable String orderNo) {
        return Result.success(orderService.cancelOrder(orderNo));
    }

    @PostMapping("/{orderNo}/pay")
    public Result<OrderVO> pay(@PathVariable String orderNo) {
        return Result.success(orderService.pay(orderNo));
    }
}
