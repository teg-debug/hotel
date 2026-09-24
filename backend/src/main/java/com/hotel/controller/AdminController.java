package com.hotel.controller;

import com.hotel.common.PageResult;
import com.hotel.common.Result;
import com.hotel.dto.AdminOrderQueryDTO;
import com.hotel.dto.BatchCreateRoomDTO;
import com.hotel.dto.CreateHotelDTO;
import com.hotel.dto.CreateOperatorDTO;
import com.hotel.dto.FrontDeskCreateDTO;
import com.hotel.dto.ResetPasswordDTO;
import com.hotel.dto.RoomStatusDTO;
import com.hotel.dto.RoomTypeSaveDTO;
import com.hotel.dto.UpdateHotelDTO;
import com.hotel.service.AdminService;
import com.hotel.vo.HotelVO;
import com.hotel.vo.OperatorVO;
import com.hotel.vo.OrderVO;
import com.hotel.vo.RoomTypeVO;
import com.hotel.vo.RoomVO;
import com.hotel.vo.StatsVO;
import com.hotel.vo.UserInfoVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台管理端（经营者/管理员）：酒店 / 房型 / 房间 / 订单 / 统计
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/hotels")
    public Result<List<HotelVO>> hotels() {
        return Result.success(adminService.listOwnHotels());
    }

    /** 系统管理员新增酒店（并指定经营者） */
    @PostMapping("/hotels")
    public Result<HotelVO> createHotel(@Valid @RequestBody CreateHotelDTO dto) {
        return Result.success(adminService.createHotel(dto));
    }

    /** 修改酒店信息（经营者仅可改名下酒店） */
    @PutMapping("/hotels/{id}")
    public Result<HotelVO> updateHotel(@PathVariable Long id, @Valid @RequestBody UpdateHotelDTO dto) {
        return Result.success(adminService.updateHotel(id, dto));
    }

    /** 经营者用户列表（管理员添加酒店时选择用） */
    @GetMapping("/operators")
    public Result<List<OperatorVO>> operators() {
        return Result.success(adminService.listOperators());
    }

    /** 系统管理员新增经营者账号 */
    @PostMapping("/operators")
    public Result<OperatorVO> createOperator(@Valid @RequestBody CreateOperatorDTO dto) {
        return Result.success(adminService.createOperator(dto));
    }

    /** 某酒店的前台账号列表 */
    @GetMapping("/hotels/{hotelId}/staff")
    public Result<List<OperatorVO>> hotelStaff(@PathVariable Long hotelId) {
        return Result.success(adminService.listHotelStaff(hotelId));
    }

    /** 为某酒店新增前台账号 */
    @PostMapping("/hotels/{hotelId}/staff")
    public Result<OperatorVO> createHotelStaff(@PathVariable Long hotelId,
                                               @Valid @RequestBody FrontDeskCreateDTO dto) {
        return Result.success(adminService.createHotelStaff(hotelId, dto));
    }

    /** 重置某酒店前台账号密码（经营者/管理员） */
    @PutMapping("/hotels/{hotelId}/staff/{staffId}/password")
    public Result<Void> resetStaffPassword(@PathVariable Long hotelId,
                                           @PathVariable Long staffId,
                                           @Valid @RequestBody ResetPasswordDTO dto) {
        adminService.resetStaffPassword(hotelId, staffId, dto);
        return Result.success();
    }

    /** 系统管理员分页查看用户（密码为 BCrypt 哈希，接口不返回该字段） */
    @GetMapping("/users")
    public Result<PageResult<UserInfoVO>> users(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return Result.success(adminService.pageUsers(page, size));
    }

    /** 系统管理员重置任意用户密码 */
    @PutMapping("/users/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordDTO dto) {
        adminService.resetPassword(id, dto);
        return Result.success();
    }

    // ---------------- 房型管理 ----------------

    @GetMapping("/hotels/{hotelId}/room-types")
    public Result<List<RoomTypeVO>> roomTypes(@PathVariable Long hotelId) {
        return Result.success(adminService.listRoomTypes(hotelId));
    }

    @PostMapping("/room-types")
    public Result<RoomTypeVO> createRoomType(@Valid @RequestBody RoomTypeSaveDTO dto) {
        return Result.success(adminService.createRoomType(dto));
    }

    @PutMapping("/room-types/{id}")
    public Result<RoomTypeVO> updateRoomType(@PathVariable Long id, @Valid @RequestBody RoomTypeSaveDTO dto) {
        return Result.success(adminService.updateRoomType(id, dto));
    }

    @DeleteMapping("/room-types/{id}")
    public Result<Void> deleteRoomType(@PathVariable Long id) {
        adminService.deleteRoomType(id);
        return Result.success();
    }

    // ---------------- 房间管理 ----------------

    @GetMapping("/room-types/{id}/rooms")
    public Result<List<RoomVO>> rooms(@PathVariable Long id) {
        return Result.success(adminService.listRooms(id));
    }

    @PostMapping("/room-types/{id}/rooms/batch")
    public Result<List<RoomVO>> batchCreateRooms(@PathVariable Long id, @Valid @RequestBody BatchCreateRoomDTO dto) {
        return Result.success(adminService.batchCreateRooms(id, dto));
    }

    @PutMapping("/rooms/{id}/status")
    public Result<RoomVO> updateRoomStatus(@PathVariable Long id, @Valid @RequestBody RoomStatusDTO dto) {
        return Result.success(adminService.updateRoomStatus(id, dto));
    }

    @DeleteMapping("/rooms/{id}")
    public Result<Void> deleteRoom(@PathVariable Long id) {
        adminService.deleteRoom(id);
        return Result.success();
    }

    @GetMapping("/orders")
    public Result<PageResult<OrderVO>> orders(@Valid AdminOrderQueryDTO dto) {
        return Result.success(adminService.pageOrders(dto));
    }

    /** 入住登记：订单 1-已确认 → 2-已入住 */
    @PutMapping("/orders/{orderNo}/checkin")
    public Result<OrderVO> checkIn(@PathVariable String orderNo) {
        return Result.success(adminService.checkIn(orderNo));
    }

    /** 退房：订单 2-已入住 → 4-已完成 + 房间置为打扫中 */
    @PutMapping("/orders/{orderNo}/checkout")
    public Result<OrderVO> checkOut(@PathVariable String orderNo) {
        return Result.success(adminService.checkOut(orderNo));
    }

    @GetMapping("/stats/occupancy")
    public Result<List<StatsVO>> occupancy(@RequestParam(required = false) String month) {
        return Result.success(adminService.occupancyStats(month));
    }
}
