package com.hotel.service;

import com.hotel.common.PageResult;
import com.hotel.dto.AdminOrderQueryDTO;
import com.hotel.dto.BatchCreateRoomDTO;
import com.hotel.dto.CreateHotelDTO;
import com.hotel.dto.CreateOperatorDTO;
import com.hotel.dto.FrontDeskCreateDTO;
import com.hotel.dto.ResetPasswordDTO;
import com.hotel.dto.RoomStatusDTO;
import com.hotel.dto.RoomTypeSaveDTO;
import com.hotel.dto.UpdateHotelDTO;
import com.hotel.vo.HotelVO;
import com.hotel.vo.OperatorVO;
import com.hotel.vo.OrderVO;
import com.hotel.vo.RoomTypeVO;
import com.hotel.vo.RoomVO;
import com.hotel.vo.StatsVO;
import com.hotel.vo.UserInfoVO;

import java.util.List;

/**
 * 后台管理（经营者/管理员）：
 * 酒店 / 房型 / 房间 / 前台账号 / 订单 / 统计，数据按 owner 隔离
 */
public interface AdminService {

    /** 当前用户可见的酒店列表（管理员见全部，经营者见名下） */
    List<HotelVO> listOwnHotels();

    /** 分页查询本店订单（经营者仅可见名下酒店订单） */
    PageResult<OrderVO> pageOrders(AdminOrderQueryDTO dto);

    /** 月度入住率统计（按月，含汇总行） */
    List<StatsVO> occupancyStats(String month);

    /** 系统管理员新增酒店并指定经营者 */
    HotelVO createHotel(CreateHotelDTO dto);

    /** 修改酒店信息（经营者仅可改名下，管理员可改全部） */
    HotelVO updateHotel(Long id, UpdateHotelDTO dto);

    /** 经营者用户列表（系统管理员分配酒店时选择用） */
    List<OperatorVO> listOperators();

    /** 系统管理员新增经营者账号（role=1） */
    OperatorVO createOperator(CreateOperatorDTO dto);

    /** 系统管理员分页查看用户（返回体含手机号等个人信息，因此不分页返回全量） */
    PageResult<UserInfoVO> pageUsers(int page, int size);

    /** 系统管理员重置任意用户密码 */
    void resetPassword(Long userId, ResetPasswordDTO dto);

    /** 经营者/管理员重置某酒店前台账号密码 */
    void resetStaffPassword(Long hotelId, Long staffId, ResetPasswordDTO dto);

    /** 某酒店的前台账号列表 */
    List<OperatorVO> listHotelStaff(Long hotelId);

    /** 为某酒店新增前台账号（role=3，绑定该酒店） */
    OperatorVO createHotelStaff(Long hotelId, FrontDeskCreateDTO dto);

    /** 某酒店的房型列表（含房间数） */
    List<RoomTypeVO> listRoomTypes(Long hotelId);

    /** 新增房型 */
    RoomTypeVO createRoomType(RoomTypeSaveDTO dto);

    /** 修改房型 */
    RoomTypeVO updateRoomType(Long id, RoomTypeSaveDTO dto);

    /** 删除房型（须先删除其下房间） */
    void deleteRoomType(Long id);

    /** 某房型下的房间列表 */
    List<RoomVO> listRooms(Long roomTypeId);

    /** 批量生成房间（房间号 = 楼层×100 + 编号，跳过已存在） */
    List<RoomVO> batchCreateRooms(Long roomTypeId, BatchCreateRoomDTO dto);

    /** 修改房间状态（0-空闲 1-停用维修 2-打扫中） */
    RoomVO updateRoomStatus(Long roomId, RoomStatusDTO dto);

    /** 删除房间（存在有效订单时不允许） */
    void deleteRoom(Long roomId);

    /** 入住登记：订单 1-已确认 → 2-已入住（经营者操作本店订单） */
    OrderVO checkIn(String orderNo);

    /** 退房：订单 2-已入住 → 4-已完成 + 房间置为打扫中 */
    OrderVO checkOut(String orderNo);
}