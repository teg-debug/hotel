package com.hotel.vo;

import com.hotel.entity.Room;
import lombok.Data;

/**
 * 房间 VO（管理端 / 前台）
 */
@Data
public class RoomVO {

    private Long id;
    private Long hotelId;
    private Long roomTypeId;
    /** 房型名称（列表展示用） */
    private String roomTypeName;
    private String roomNo;
    private Integer floor;
    /** 物理状态：0-空闲 1-停用维修 2-打扫中 */
    private Integer status;
    /**
     * 是否存在在住订单（派生字段）。
     * 房间状态不再表示「已订」，在住与否由订单日期区间推导，因此单独返回。
     */
    private Boolean occupied = false;

    public static RoomVO from(Room room) {
        RoomVO vo = new RoomVO();
        vo.setId(room.getId());
        vo.setHotelId(room.getHotelId());
        vo.setRoomTypeId(room.getRoomTypeId());
        vo.setRoomNo(room.getRoomNo());
        vo.setFloor(room.getFloor());
        vo.setStatus(room.getStatus());
        return vo;
    }
}
