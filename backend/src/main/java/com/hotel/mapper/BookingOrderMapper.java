package com.hotel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hotel.entity.BookingOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface BookingOrderMapper extends BaseMapper<BookingOrder> {

    /**
     * 统计某酒店某房型在 [checkin, checkout) 区间内有效订单数。
     * 有效状态：0-待支付 1-已确认 2-已入住（已取消/已完成不占房）
     */
    @Select("SELECT COUNT(*) FROM booking_order " +
            "WHERE hotel_id = #{hotelId} AND room_type_id = #{roomTypeId} " +
            "AND status IN (0, 1, 2) " +
            "AND checkin_date < #{checkout} AND checkout_date > #{checkin}")
    Long countConflict(@Param("hotelId") Long hotelId,
                       @Param("roomTypeId") Long roomTypeId,
                       @Param("checkin") LocalDate checkin,
                       @Param("checkout") LocalDate checkout);

    /**
     * 统计某酒店某房型在 [checkin, checkout) 区间内「真正可售」的房间数。
     *
     * <p>可售 = 房间物理状态为空闲（0）且在该区间内没有有效订单。
     * 这是一次查询得出可用数，取代原先「空闲房间数 - 冲突订单数」的两次统计，
     * 避免同一批房间被重复扣减。</p>
     *
     * <p>该单房型查询已被 {@code RoomMapper.selectAvailableRoomTypeIds} 的批量版本取代：
     * 搜索页一次要统计整页酒店的房型可售数，逐房型调用会退化成 N+1（一页 10 家酒店 20 条 SQL）。</p>
     */
    @Select("SELECT COUNT(*) FROM room " +
            "WHERE hotel_id = #{hotelId} AND room_type_id = #{roomTypeId} AND status = 0 " +
            "AND id NOT IN (" +
            "    SELECT room_id FROM booking_order " +
            "    WHERE hotel_id = #{hotelId} AND status IN (0, 1, 2) " +
            "    AND checkin_date < #{checkout} AND checkout_date > #{checkin}" +
            ")")
    Long countAvailableRooms(@Param("hotelId") Long hotelId,
                             @Param("roomTypeId") Long roomTypeId,
                             @Param("checkin") LocalDate checkin,
                             @Param("checkout") LocalDate checkout);

    /**
     * 批量统计多个房型在 [checkin, checkout) 区间内的可售房间数。
     *
     * <p>返回「房型ID + 可售数」，一次查询覆盖整页酒店的全部房型。</p>
     */
    @Select("<script>" +
            "SELECT room_type_id AS roomTypeId, COUNT(*) AS availableCount FROM room " +
            "WHERE hotel_id IN " +
            "<foreach collection='hotelIds' item='hotelId' open='(' separator=',' close=')'>#{hotelId}</foreach> " +
            "AND room_type_id IN " +
            "<foreach collection='roomTypeIds' item='roomTypeId' open='(' separator=',' close=')'>#{roomTypeId}</foreach> " +
            "AND status = 0 " +
            "AND id NOT IN (" +
            "    SELECT room_id FROM booking_order " +
            "    WHERE status IN (0, 1, 2) " +
            "    AND checkin_date &lt; #{checkout} AND checkout_date &gt; #{checkin}" +
            ") " +
            "GROUP BY room_type_id" +
            "</script>")
    List<Map<String, Object>> countAvailableRoomsBatch(@Param("hotelIds") List<Long> hotelIds,
                                                       @Param("roomTypeIds") List<Long> roomTypeIds,
                                                       @Param("checkin") LocalDate checkin,
                                                       @Param("checkout") LocalDate checkout);

    /**
     * 锁定查询某房间在指定区间内的其他有效订单。
     *
     * <p>使用加锁读（当前读）而非快照读，配合下单后的复核，
     * 可以在分布式锁失效时兜住「同一房间被重复售出」的情况。</p>
     */
    @Select("SELECT id FROM booking_order " +
            "WHERE room_id = #{roomId} AND status IN (0, 1, 2) " +
            "AND checkin_date < #{checkout} AND checkout_date > #{checkin} " +
            "FOR UPDATE")
    List<Long> lockOverlappingOrderIds(@Param("roomId") Long roomId,
                                       @Param("checkin") LocalDate checkin,
                                       @Param("checkout") LocalDate checkout);

    /** 统计某酒店在 [startDate, endDate) 区间内的已售间夜数（已确认/已入住/已完成） */
    @Select("SELECT COALESCE(SUM(night_count), 0) FROM booking_order " +
            "WHERE hotel_id = #{hotelId} AND status IN (1, 2, 4) " +
            "AND checkin_date < #{endDate} AND checkout_date > #{startDate}")
    Long sumOccupiedNights(@Param("hotelId") Long hotelId,
                           @Param("startDate") LocalDate startDate,
                           @Param("endDate") LocalDate endDate);

    /**
     * 统计某酒店在 [startDate, endDate) 区间内、只计算落在区间内的已售间夜数。
     *
     * <p>与 {@link #sumOccupiedNights} 的区别在于按区间边界裁剪：
     * 跨月订单只把落在本月的那部分间夜计入，避免整段计入当月导致入住率虚高。</p>
     */
    @Select("SELECT COALESCE(SUM(DATEDIFF(LEAST(checkout_date, #{endDate}), " +
            "GREATEST(checkin_date, #{startDate}))), 0) FROM booking_order " +
            "WHERE hotel_id = #{hotelId} AND status IN (1, 2, 4) " +
            "AND checkin_date < #{endDate} AND checkout_date > #{startDate}")
    Long sumOccupiedNightsInRange(@Param("hotelId") Long hotelId,
                                  @Param("startDate") LocalDate startDate,
                                  @Param("endDate") LocalDate endDate);

    /**
     * 批量查询当前处于「在住」状态的房间ID（今日落在已入住订单的日期区间内）。
     * 房间状态不再表示「已订」，前台与后台列表需要据此单独展示在住标记。
     */
    @Select("<script>" +
            "SELECT DISTINCT room_id FROM booking_order " +
            "WHERE status = 2 AND checkin_date &lt;= #{today} AND checkout_date &gt; #{today} " +
            "AND room_id IN " +
            "<foreach collection='roomIds' item='roomId' open='(' separator=',' close=')'>#{roomId}</foreach>" +
            "</script>")
    List<Long> selectInHouseRoomIds(@Param("roomIds") List<Long> roomIds, @Param("today") LocalDate today);

    /** 统计某酒店在 [startDate, endDate) 区间内的有效订单数（已确认/已入住/已完成） */
    @Select("SELECT COUNT(*) FROM booking_order " +
            "WHERE hotel_id = #{hotelId} AND status IN (1, 2, 4) " +
            "AND checkin_date < #{endDate} AND checkout_date > #{startDate}")
    Long countValidOrders(@Param("hotelId") Long hotelId,
                          @Param("startDate") LocalDate startDate,
                          @Param("endDate") LocalDate endDate);

    /** 统计某房间的有效订单数（用于删除房间前的校验） */
    @Select("SELECT COUNT(*) FROM booking_order " +
            "WHERE room_id = #{roomId} AND status IN (0, 1, 2)")
    Long countActiveByRoom(@Param("roomId") Long roomId);

    /**
     * 某房间当前是否处于「在住」状态（今日有已入住订单）。
     * 用于房态维护时的合法性校验。
     */
    @Select("SELECT COUNT(*) FROM booking_order " +
            "WHERE room_id = #{roomId} AND status = 2 " +
            "AND checkin_date <= #{today} AND checkout_date > #{today}")
    Long countInHouseByRoom(@Param("roomId") Long roomId, @Param("today") LocalDate today);

    /** 某房间是否存在尚未结束的有效订单（用于停用房间前的校验） */
    @Select("SELECT COUNT(*) FROM booking_order " +
            "WHERE room_id = #{roomId} AND status IN (0, 1, 2) AND checkout_date > #{today}")
    Long countPendingFromToday(@Param("roomId") Long roomId, @Param("today") LocalDate today);
}
