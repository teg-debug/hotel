package com.hotel.service;

import com.hotel.common.PageResult;
import com.hotel.dto.OrderQueryDTO;
import com.hotel.vo.OrderDetailVO;
import com.hotel.vo.OrderVO;

/**
 * 用户端订单：查询 / 详情 / 取消 / 模拟支付
 */
public interface OrderService {

    /** 分页查询当前用户订单 */
    PageResult<OrderVO> pageMyOrders(OrderQueryDTO dto);

    /** 查询单笔订单详情（含支付与退款流水），仅限本人订单 */
    OrderDetailVO getOrderDetail(String orderNo);

    /** 取消订单（仅待支付） */
    OrderVO cancelOrder(String orderNo);

    /** 模拟支付：生成流水号并触发支付回调 */
    OrderVO pay(String orderNo);
}
