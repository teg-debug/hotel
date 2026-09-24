package com.hotel.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hotel.common.BusinessException;
import com.hotel.common.SearchCacheSupport;
import com.hotel.config.BookingProperties;
import com.hotel.config.MemberProperties;
import com.hotel.dto.CreateOrderDTO;
import com.hotel.dto.PaymentCallbackDTO;
import com.hotel.entity.BookingOrder;
import com.hotel.entity.Hotel;
import com.hotel.entity.PaymentLog;
import com.hotel.entity.Room;
import com.hotel.entity.RoomType;
import com.hotel.entity.User;
import com.hotel.mapper.BookingOrderMapper;
import com.hotel.mapper.HotelMapper;
import com.hotel.mapper.PaymentLogMapper;
import com.hotel.mapper.RoomMapper;
import com.hotel.mapper.RoomTypeMapper;
import com.hotel.mapper.UserMapper;
import com.hotel.service.OrderPreparation;
import com.hotel.service.OrderTxService;
import com.hotel.service.RoomUnavailableException;
import com.hotel.utils.OrderNoGenerator;
import com.hotel.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 下单与订单状态流转的事务服务。
 *
 * <p>库存口径：房间的 {@code status} 只表示物理/运营状态（0-空闲 1-停用 2-打扫中），
 * 「某天是否已被占用」完全由订单表的日期区间决定。因此下单不再把房间置为「已订」，
 * 否则一间房被订过任意一天后，会在所有日期上停售。</p>
 *
 * <p>并发防护分三层：房间级 Redisson 锁（{@link com.hotel.service.BookingService}）→ 选房时
 * {@code FOR UPDATE} 行锁 → 下单后以加锁读复核区间内订单数。只有后两层是正确性来源，
 * 分布式锁只负责降低冲突概率，因此锁失效或候选房间被抢走只会降吞吐，不会超订。</p>
 *
 * <p>注意职责边界：{@link #prepare} 是锁外的只读准备（校验 + 算价），不进事务也不持有锁；
 * {@link #createOrder(OrderPreparation)} 才是锁内的临界区，只做「选房 + 落单 + 复核」。
 * 这样切分是为了让临界区尽可能短——热点房型的吞吐上限就是 {@code 1 / 临界区耗时}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTxServiceImpl implements OrderTxService {

    private static final int ORDER_EXPIRE_MINUTES = 15;

    private final HotelMapper hotelMapper;
    private final RoomTypeMapper roomTypeMapper;
    private final RoomMapper roomMapper;
    private final UserMapper userMapper;
    private final BookingOrderMapper bookingOrderMapper;
    private final PaymentLogMapper paymentLogMapper;
    private final MemberProperties memberProperties;
    private final BookingProperties bookingProperties;
    private final SearchCacheSupport searchCacheSupport;

    @Override
    public OrderPreparation prepare(CreateOrderDTO dto, Long userId) {
        // 1. 酒店 / 房型存在性与状态校验
        Hotel hotel = hotelMapper.selectById(dto.getHotelId());
        if (hotel == null || hotel.getStatus() == 0) {
            throw new BusinessException("酒店不存在或已下架");
        }
        RoomType roomType = roomTypeMapper.selectById(dto.getRoomTypeId());
        if (roomType == null || !roomType.getHotelId().equals(hotel.getId())) {
            throw new BusinessException("房型不存在");
        }
        if (roomType.getStatus() == 0) {
            throw new BusinessException("该房型已停售");
        }

        // 2. 用户与会员折扣 → 金额（晚数 * 折扣后单价）
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        long nights = ChronoUnit.DAYS.between(dto.getCheckinDate(), dto.getCheckoutDate());
        if (nights <= 0) {
            throw new BusinessException("离店日期必须晚于入住日期");
        }
        BigDecimal discount = memberProperties.discountOf(user.getMemberLevel());
        BigDecimal unitPrice = roomType.getPrice().multiply(discount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(nights));

        // 3. 候选房间：用户指定房间时只有它一个候选；否则锁外快照一个可售房间 ID 池并打散。
        //    打散是必须的——若所有请求都按 ID 顺序从同一间房开始抢，房间级锁会退化成
        //    「第一间房的锁」，热点依旧，还会白白多出大量换候选重试。
        List<Long> candidateRoomIds;
        if (dto.getRoomId() != null) {
            candidateRoomIds = List.of(dto.getRoomId());
        } else {
            candidateRoomIds = new ArrayList<>(roomMapper.selectAvailableRoomIds(
                    hotel.getId(), roomType.getId(), dto.getCheckinDate(), dto.getCheckoutDate(),
                    bookingProperties.effectiveCandidatePoolSize()));
            Collections.shuffle(candidateRoomIds);
        }

        return new OrderPreparation(userId, hotel, roomType, (int) nights,
                unitPrice, discount, totalAmount,
                dto.getCheckinDate(), dto.getCheckoutDate(),
                dto.getGuestName(), dto.getGuestPhone(), dto.getRoomId(), candidateRoomIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO createOrder(OrderPreparation preparation, Long roomId) {
        Long hotelId = preparation.hotel().getId();
        Long roomTypeId = preparation.roomType().getId();
        LocalDate checkinDate = preparation.checkinDate();
        LocalDate checkoutDate = preparation.checkoutDate();

        // 1. 选房复核：以加锁读确认该房间仍空闲、状态可售且区间无冲突。
        //    候选池来自锁外快照，可能已被并发请求抢走——这里就是剔除它的地方。
        Room room = roomMapper.selectAvailableRoomById(hotelId, roomTypeId, roomId, checkinDate, checkoutDate);
        if (room == null) {
            throw new RoomUnavailableException(preparation.requestedRoomId() != null
                    ? "您选择的房间已被占用或不可用，请重新选择房间"
                    : "该房间在所选日期已被预订，请重新选择房间");
        }

        // 2. 生成订单：状态 0-待支付，15 分钟超时未支付由定时任务自动取消
        BookingOrder order = new BookingOrder();
        order.setOrderNo(OrderNoGenerator.generate());
        order.setUserId(preparation.userId());
        order.setHotelId(hotelId);
        order.setHotelName(preparation.hotel().getName());
        order.setRoomTypeId(roomTypeId);
        order.setRoomTypeName(preparation.roomType().getName());
        order.setRoomId(room.getId());
        order.setRoomNo(room.getRoomNo());
        order.setCheckinDate(checkinDate);
        order.setCheckoutDate(checkoutDate);
        order.setNightCount(preparation.nights());
        order.setGuestName(preparation.guestName());
        order.setGuestPhone(preparation.guestPhone());
        order.setRoomPrice(preparation.unitPrice());
        order.setMemberDiscount(preparation.discount());
        order.setTotalAmount(preparation.totalAmount());
        order.setStatus(0);
        order.setExpireTime(LocalDateTime.now().plusMinutes(ORDER_EXPIRE_MINUTES));
        bookingOrderMapper.insert(order);

        // 3. 复核：以加锁读确认该房间在区间内只存在本条订单，兜住分布式锁失效的极端情况
        List<Long> overlapping = bookingOrderMapper.lockOverlappingOrderIds(
                room.getId(), checkinDate, checkoutDate);
        if (overlapping.size() > 1) {
            log.warn("检测到房间重复售出，已回滚 roomId={} orderNo={} 冲突订单数={}",
                    room.getId(), order.getOrderNo(), overlapping.size());
            throw new RoomUnavailableException("该房间在所选日期已被预订，请重新选择房间");
        }

        log.info("订单创建成功 orderNo={} userId={} hotelId={} roomTypeId={} roomId={} {} 至 {} 共 {} 晚 金额={}",
                order.getOrderNo(), preparation.userId(), hotelId, roomTypeId, room.getId(),
                checkinDate, checkoutDate, preparation.nights(), preparation.totalAmount());
        return OrderVO.from(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmOrder(BookingOrder order, PaymentCallbackDTO dto) {
        PaymentLog existing = findPayLog(dto.getPayNo());
        // 同一流水号已成功入账：重复回调直接返回（幂等）
        if (existing != null && Integer.valueOf(PaymentLog.STATUS_SUCCESS).equals(existing.getStatus())) {
            log.debug("支付回调重复，已忽略 orderNo={} payNo={}", order.getOrderNo(), dto.getPayNo());
            return;
        }
        // 金额校验：回调金额必须与订单金额一致
        if (dto.getAmount() == null || order.getTotalAmount() == null
                || dto.getAmount().compareTo(order.getTotalAmount()) != 0) {
            throw new BusinessException("支付金额与订单金额不一致");
        }
        // 状态流转 0-待支付 → 1-已确认（条件更新：与超时关单并发时仅一方成功）
        int updated = bookingOrderMapper.update(null, new LambdaUpdateWrapper<BookingOrder>()
                .eq(BookingOrder::getId, order.getId())
                .eq(BookingOrder::getStatus, 0)
                .set(BookingOrder::getStatus, 1)
                .set(BookingOrder::getPayTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new BusinessException("订单状态已变更，无法支付");
        }
        if (existing != null) {
            // 同一流水号此前回调失败，改为更新原行，避免与 pay_no 唯一键冲突
            existing.setStatus(PaymentLog.STATUS_SUCCESS);
            existing.setAmount(dto.getAmount());
            existing.setPayType(dto.getPayType() == null ? 0 : dto.getPayType());
            existing.setCallbackTime(LocalDateTime.now());
            paymentLogMapper.updateById(existing);
        } else {
            paymentLogMapper.insert(buildPayLog(order, dto, PaymentLog.BIZ_PAY, PaymentLog.STATUS_SUCCESS));
        }
        log.info("订单支付入账 orderNo={} payNo={} 金额={} payType={}",
                order.getOrderNo(), dto.getPayNo(), dto.getAmount(), dto.getPayType());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelOrder(BookingOrder order, String reason) {
        // 状态流转 0-待支付 → 3-已取消（条件更新，防止与支付回调并发冲突）
        int updated = bookingOrderMapper.update(null, new LambdaUpdateWrapper<BookingOrder>()
                .eq(BookingOrder::getId, order.getId())
                .eq(BookingOrder::getStatus, 0)
                .set(BookingOrder::getStatus, 3)
                .set(BookingOrder::getCancelReason, reason));
        if (updated == 0) {
            return false;
        }
        // 房间状态不再随下单/取消变化，可售性完全由订单日期区间决定，因此无需释放房间
        invalidateSearchCache();
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean refundOrder(BookingOrder order, String refundNo, String reason) {
        // 状态流转 1-已确认 → 3-已取消（条件更新：已入住/已取消的订单不再受理）
        int updated = bookingOrderMapper.update(null, new LambdaUpdateWrapper<BookingOrder>()
                .eq(BookingOrder::getId, order.getId())
                .eq(BookingOrder::getStatus, 1)
                .set(BookingOrder::getStatus, 3)
                .set(BookingOrder::getCancelReason, reason));
        if (updated == 0) {
            return false;
        }
        PaymentLog refundLog = new PaymentLog();
        refundLog.setOrderId(order.getId());
        refundLog.setOrderNo(order.getOrderNo());
        refundLog.setPayNo(refundNo);
        refundLog.setBizType(PaymentLog.BIZ_REFUND);
        refundLog.setPayType(0);
        refundLog.setAmount(order.getTotalAmount());
        refundLog.setStatus(PaymentLog.STATUS_SUCCESS);
        refundLog.setCallbackTime(LocalDateTime.now());
        paymentLogMapper.insert(refundLog);

        invalidateSearchCache();
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkIn(BookingOrder order) {
        // 状态流转 1-已确认 → 2-已入住（条件更新，防止与取消/并发冲突）
        int updated = bookingOrderMapper.update(null, new LambdaUpdateWrapper<BookingOrder>()
                .eq(BookingOrder::getId, order.getId())
                .eq(BookingOrder::getStatus, 1)
                .set(BookingOrder::getStatus, 2));
        if (updated == 0) {
            throw new BusinessException("订单状态已变更，无法办理入住");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkOut(BookingOrder order) {
        // 状态流转 2-已入住 → 4-已完成（条件更新，防止并发冲突）
        int updated = bookingOrderMapper.update(null, new LambdaUpdateWrapper<BookingOrder>()
                .eq(BookingOrder::getId, order.getId())
                .eq(BookingOrder::getStatus, 2)
                .set(BookingOrder::getStatus, 4));
        if (updated == 0) {
            throw new BusinessException("订单状态已变更，无法办理退房");
        }
        // 房间置为「打扫中」，清洁后由经营者或前台置回「空闲」
        roomMapper.update(null, new LambdaUpdateWrapper<Room>()
                .eq(Room::getId, order.getRoomId())
                .ne(Room::getStatus, Room.STATUS_CLEANING)
                .set(Room::getStatus, Room.STATUS_CLEANING));
        invalidateSearchCache();
    }

    private PaymentLog findPayLog(String payNo) {
        return paymentLogMapper.selectOne(new LambdaQueryWrapper<PaymentLog>()
                .eq(PaymentLog::getPayNo, payNo)
                .eq(PaymentLog::getBizType, PaymentLog.BIZ_PAY)
                .last("LIMIT 1"));
    }

    private PaymentLog buildPayLog(BookingOrder order, PaymentCallbackDTO dto, int bizType, int status) {
        PaymentLog log = new PaymentLog();
        log.setOrderId(order.getId());
        log.setOrderNo(order.getOrderNo());
        log.setPayNo(dto.getPayNo());
        log.setBizType(bizType);
        log.setPayType(dto.getPayType() == null ? 0 : dto.getPayType());
        log.setAmount(dto.getAmount());
        log.setStatus(status);
        log.setCallbackTime(LocalDateTime.now());
        return log;
    }

    private void invalidateSearchCache() {
        // 版本号自增即可让现有搜索结果全部失效，无需遍历键空间
        searchCacheSupport.invalidate();
    }
}
