package com.hotel.controller;

import com.hotel.common.Result;
import com.hotel.dto.RoomStatusDTO;
import com.hotel.service.StaffService;
import com.hotel.vo.HotelVO;
import com.hotel.vo.RoomVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 酒店前台工作台：房间状态管理（入住/清理/空闲），仅限绑定酒店
 */
@RestController
@RequestMapping("/api/v1/staff")
@RequiredArgsConstructor
public class StaffController {

    private final StaffService staffService;

    /** 绑定的酒店信息 */
    @GetMapping("/hotel")
    public Result<HotelVO> hotel() {
        return Result.success(staffService.myHotel());
    }

    /** 绑定酒店的房间列表 */
    @GetMapping("/rooms")
    public Result<List<RoomVO>> rooms() {
        return Result.success(staffService.listRooms());
    }

    /** 修改房间状态：0-空闲 / 1-入住 / 2-打扫中 */
    @PutMapping("/rooms/{id}/status")
    public Result<RoomVO> updateRoomStatus(@PathVariable Long id, @Valid @RequestBody RoomStatusDTO dto) {
        return Result.success(staffService.updateRoomStatus(id, dto));
    }
}
