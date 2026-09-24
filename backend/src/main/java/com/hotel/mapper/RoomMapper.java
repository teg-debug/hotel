package com.hotel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hotel.entity.Room;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface RoomMapper extends BaseMapper<Room> {

    /**
     * 查询指定房型在日期区间内的全部可用房间（供用户挑选，无锁）
     */
    @Select("SELECT * FROM room " +
            "WHERE hotel_id = #{hotelId} AND room_type_id = #{roomTypeId} AND status = 0 " +
            "AND id NOT IN (" +
            "    SELECT room_id FROM booking_order " +
            "    WHERE hotel_id = #{hotelId} AND status IN (0, 1, 2) " +
            "    AND checkin_date < #{checkout} AND checkout_date > #{checkin}" +
            ") " +
            "ORDER BY id")
    List<Room> selectAvailableRooms(@Param("hotelId") Long hotelId,
                                    @Param("roomTypeId") Long roomTypeId,
                                    @Param("checkin") LocalDate checkin,
                                    @Param("checkout") LocalDate checkout);

    /**
     * 批量统计多个房型「空闲」的房间数（不带入住日期时的搜索页统计）。
     *
     * <p>与 {@code BookingOrderMapper.countAvailableRoomsBatch} 成对：带日期时按区间冲突统计，
     * 不带日期时只看房间物理状态。两者都是「一次覆盖整页房型」，取代逐房型 COUNT。
     * 用 SQL 分组而不是回传房间 ID 列表，是为了少传几百行数据。</p>
     */
    @Select("<script>" +
            "SELECT room_type_id AS roomTypeId, COUNT(*) AS availableCount FROM room " +
            "WHERE hotel_id IN " +
            "<foreach collection='hotelIds' item='hotelId' open='(' separator=',' close=')'>#{hotelId}</foreach> " +
            "AND room_type_id IN " +
            "<foreach collection='roomTypeIds' item='roomTypeId' open='(' separator=',' close=')'>#{roomTypeId}</foreach> " +
            "AND status = 0 " +
            "GROUP BY room_type_id" +
            "</script>")
    List<Map<String, Object>> countIdleRoomsBatch(@Param("hotelIds") List<Long> hotelIds,
                                                  @Param("roomTypeIds") List<Long> roomTypeIds);

    /**
     * 取该房型在区间内可售房间的 ID 池（快照读、无锁），供下单时逐个尝试候选房间。
     *
     * <p>只查 ID 且限量：相比整行读取，大库存房型的这次快照读代价小得多。
     * 结果可能包含「刚被别的请求抢走」的房间——这是允许的，锁内会以加锁读复核剔除。</p>
     */
    @Select("SELECT id FROM room " +
            "WHERE hotel_id = #{hotelId} AND room_type_id = #{roomTypeId} AND status = 0 " +
            "AND id NOT IN (" +
            "    SELECT room_id FROM booking_order " +
            "    WHERE hotel_id = #{hotelId} AND status IN (0, 1, 2) " +
            "    AND checkin_date < #{checkout} AND checkout_date > #{checkin}" +
            ") " +
            "ORDER BY id LIMIT #{limit}")
    List<Long> selectAvailableRoomIds(@Param("hotelId") Long hotelId,
                                      @Param("roomTypeId") Long roomTypeId,
                                      @Param("checkin") LocalDate checkin,
                                      @Param("checkout") LocalDate checkout,
                                      @Param("limit") int limit);

    /**
     * 锁定用户指定的房间：空闲且与日期区间无冲突才返回（FOR UPDATE，用于下单防并发）
     */
    @Select("SELECT * FROM room " +
            "WHERE id = #{roomId} AND hotel_id = #{hotelId} AND room_type_id = #{roomTypeId} AND status = 0 " +
            "AND id NOT IN (" +
            "    SELECT room_id FROM booking_order " +
            "    WHERE hotel_id = #{hotelId} AND status IN (0, 1, 2) " +
            "    AND checkin_date < #{checkout} AND checkout_date > #{checkin}" +
            ") " +
            "LIMIT 1 FOR UPDATE")
    Room selectAvailableRoomById(@Param("hotelId") Long hotelId,
                                 @Param("roomTypeId") Long roomTypeId,
                                 @Param("roomId") Long roomId,
                                 @Param("checkin") LocalDate checkin,
                                 @Param("checkout") LocalDate checkout);
}
