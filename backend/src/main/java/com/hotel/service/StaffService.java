package com.hotel.service;

import com.hotel.dto.RoomStatusDTO;
import com.hotel.vo.HotelVO;
import com.hotel.vo.RoomVO;

import java.util.List;

/**
 * 酒店前台工作台：仅可操作绑定酒店的房间状态（入住/清理/空闲），
 * 不可新增房型/房间、不可管理订单
 */
public interface StaffService {

    /** 当前绑定的酒店信息 */
    HotelVO myHotel();

    /** 绑定酒店的房间列表 */
    List<RoomVO> listRooms();

    /** 修改房间状态（0-空闲 / 1-入住 / 2-打扫中） */
    RoomVO updateRoomStatus(Long roomId, RoomStatusDTO dto);
}
