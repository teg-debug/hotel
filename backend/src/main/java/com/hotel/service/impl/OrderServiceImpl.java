package com.hotel.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hotel.common.BusinessException;
import com.hotel.common.PageResult;
import com.hotel.common.ResultCode;
import com.hotel.dto.OrderQueryDTO;
import com.hotel.entity.BookingOrder;
import com.hotel.entity.PaymentLog;
import com.hotel.mapper.BookingOrderMapper;
import com.hotel.mapper.PaymentLogMapper;
import com.hotel.security.UserContext;
import com.hotel.service.OrderService;
import com.hotel.service.OrderTxService;
import com.hotel.service.PaymentGateway;
import com.hotel.vo.OrderDetailVO;
import com.hotel.vo.OrderVO;
import com.hotel.vo.PaymentLogVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final BookingOrderMapper bookingOrderMapper;
    private final PaymentLogMapper paymentLogMapper;
    private final OrderTxService orderTxService;
    private final PaymentGateway paymentGateway;

    @Override
    public PageResult<OrderVO> pageMyOrders(OrderQueryDTO dto) {
        Long userId = UserContext.getUserId();
        LambdaQueryWrapper<BookingOrder> wrapper = new LambdaQueryWrapper<BookingOrder>()
                .eq(BookingOrder::getUserId, userId)
                .eq(dto.getStatus() != null, BookingOrder::getStatus, dto.getStatus())
                .orderByDesc(BookingOrder::getCreateTime);
        Page<BookingOrder> page = bookingOrderMapper.selectPage(new Page<>(dto.getPage(), dto.getSize()), wrapper);
        List<OrderVO> records = page.getRecords().stream().map(OrderVO::from).toList();
        return PageResult.of(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), records);
    }

    /**
     * 订单详情：归属校验复用 getOwnOrder，非本人订单返回 403。
     *
     * <p>流水按 id 正序返回，前端可直接按时间顺序展示支付与退款。</p>
     */
    @Override
    public OrderDetailVO getOrderDetail(String orderNo) {
        BookingOrder order = getOwnOrder(orderNo);
        List<PaymentLogVO> payments = paymentLogMapper.selectList(
                        new LambdaQueryWrapper<PaymentLog>()
                                .eq(PaymentLog::getOrderId, order.getId())
                                .orderByAsc(PaymentLog::getId))
                .stream().map(PaymentLogVO::from).toList();
        return OrderDetailVO.from(order, payments);
    }

    /**
     * 取消订单：待支付订单直接取消；已确认且未入住的订单走退款后取消。
     * 已入住订单需到前台办理退房，避免线上绕过在住流程。
     */
    @Override
    public OrderVO cancelOrder(String orderNo) {
        BookingOrder order = getOwnOrder(orderNo);
        Integer status = order.getStatus();
        if (status == null) {
            throw new BusinessException("订单状态异常，无法取消");
        }
        String reason;
        boolean done;
        if (status == 0) {
            reason = "用户取消";
            done = orderTxService.cancelOrder(order, reason);
        } else if (status == 1) {
            reason = "用户取消并退款";
            String refundNo = paymentGateway.refund(order, reason);
            done = orderTxService.refundOrder(order, refundNo, reason);
        } else if (status == 2) {
            throw new BusinessException("订单已入住，请到前台办理退房");
        } else {
            throw new BusinessException("当前订单状态不可取消");
        }
        if (!done) {
            throw new BusinessException("订单状态已变更，无法取消");
        }
        order.setStatus(3);
        order.setCancelReason(reason);
        return OrderVO.from(order);
    }

    /**
     * 模拟支付：仅当装配的是模拟网关时可用。
     * 接入真实网关后该方法会被拒绝，前端应改为通过 prepay 参数唤起收银台。
     */
    @Override
    public OrderVO pay(String orderNo) {
        BookingOrder order = getOwnOrder(orderNo);
        if (order.getStatus() != 0) {
            throw new BusinessException("订单状态已变更，无法支付");
        }
        if (order.getExpireTime() != null && order.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException("订单已超时，请重新下单");
        }
        if (!paymentGateway.supportsSimulation()) {
            throw new BusinessException("系统未启用模拟支付，请通过支付网关完成支付");
        }
        paymentGateway.simulatePayment(order);
        return OrderVO.from(bookingOrderMapper.selectOne(
                new LambdaQueryWrapper<BookingOrder>().eq(BookingOrder::getOrderNo, orderNo)));
    }

    private BookingOrder getOwnOrder(String orderNo) {
        BookingOrder order = bookingOrderMapper.selectOne(
                new LambdaQueryWrapper<BookingOrder>().eq(BookingOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (!order.getUserId().equals(UserContext.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该订单");
        }
        return order;
    }
}
