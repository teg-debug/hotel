package com.hotel.controller;

import com.hotel.common.Result;
import com.hotel.dto.CreateOrderDTO;
import com.hotel.service.BookingService;
import com.hotel.vo.OrderVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预订下单：Redisson 分布式锁防超卖，生成待支付订单
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping("/create")
    public Result<OrderVO> create(@Valid @RequestBody CreateOrderDTO dto) {
        return Result.success(bookingService.createOrder(dto));
    }
}
