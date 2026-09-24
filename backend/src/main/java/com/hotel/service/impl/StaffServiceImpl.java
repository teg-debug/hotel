package com.hotel.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.common.BusinessException;
import com.hotel.common.ResultCode;
import com.hotel.common.SearchCacheSupport;
import com.hotel.dto.RoomStatusDTO;
import com.hotel.entity.Hotel;
import com.hotel.entity.Room;
import com.hotel.entity.RoomType;
import com.hotel.entity.User;
import com.hotel.mapper.BookingOrderMapper;
import com.hotel.mapper.HotelMapper;
import com.hotel.mapper.RoomMapper;
import com.hotel.mapper.RoomTypeMapper;
import com.hotel.mapper.UserMapper;
import com.hotel.security.UserContext;
import com.hotel.service.StaffService;
import com.hotel.vo.HotelVO;
import com.hotel.vo.RoomVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 酒店前台工作台：本酒店信息、房间列表与房态维护。
 *
 * <p>房态校验与管理端保持同一口径：房间状态只表示物理与运营状态，
 * 因此「有客在住时不能置为空闲」「存在未结束订单时不能停用」是必须的守卫，
 * 否则已订房间被改成空闲后可能被再次售出。</p>
 */
@Service
@RequiredArgsConstructor
public class StaffServiceImpl implements StaffService {

    private static final int ROLE_STAFF = 3;

    private final UserMapper userMapper;
    private final HotelMapper hotelMapper;
    private final RoomMapper roomMapper;
    private final RoomTypeMapper roomTypeMapper;
    private final BookingOrderMapper bookingOrderMapper;
    private final SearchCacheSupport searchCacheSupport;

    /** 校验当前用户为前台账号并返回其绑定信息 */
    private User requireStaff() {
        Integer role = UserContext.getRole();
        if (role == null || role != ROLE_STAFF) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权限访问");
        }
        User user = userMapper.selectById(UserContext.getUserId());
        if (user == null || user.getHotelId() == null) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号未绑定酒店");
        }
        return user;
    }

    @Override
    public HotelVO myHotel() {
        User staff = requireStaff();
        Hotel hotel = hotelMapper.selectById(staff.getHotelId());
        if (hotel == null) {
            throw new BusinessException("绑定的酒店不存在");
        }
        return HotelVO.from(hotel);
    }

    @Override
    public List<RoomVO> listRooms() {
        User staff = requireStaff();
        Map<Long, String> typeNames = roomTypeMapper.selectList(new LambdaQueryWrapper<RoomType>()
                        .eq(RoomType::getHotelId, staff.getHotelId()))
                .stream().collect(Collectors.toMap(RoomType::getId, RoomType::getName, (a, b) -> a));

        List<Room> rooms = roomMapper.selectList(new LambdaQueryWrapper<Room>()
                .eq(Room::getHotelId, staff.getHotelId())
                .orderByAsc(Room::getRoomNo));
        List<RoomVO> vos = rooms.stream().map(room -> {
            RoomVO vo = RoomVO.from(room);
            vo.setRoomTypeName(typeNames.get(room.getRoomTypeId()));
            return vo;
        }).toList();

        // 一次查询批量补全在住标记，避免逐间查询
        if (!rooms.isEmpty()) {
            List<Long> roomIds = rooms.stream().map(Room::getId).toList();
            List<Long> inHouseIds = bookingOrderMapper.selectInHouseRoomIds(roomIds, LocalDate.now());
            if (inHouseIds != null && !inHouseIds.isEmpty()) {
                Set<Long> inHouseSet = new HashSet<>(inHouseIds);
                vos.forEach(vo -> vo.setOccupied(inHouseSet.contains(vo.getId())));
            }
        }
        return vos;
    }

    @Override
    public RoomVO updateRoomStatus(Long roomId, RoomStatusDTO dto) {
        User staff = requireStaff();
        Room room = roomMapper.selectById(roomId);
        if (room == null) {
            throw new BusinessException("房间不存在");
        }
        // 前台仅能操作绑定酒店的房间
        if (!room.getHotelId().equals(staff.getHotelId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该房间");
        }

        Integer target = dto.getStatus();
        LocalDate today = LocalDate.now();
        if (target == Room.STATUS_IDLE) {
            Long inHouse = bookingOrderMapper.countInHouseByRoom(roomId, today);
            if (inHouse != null && inHouse > 0) {
                throw new BusinessException("该房间当前有客在住，不能置为空闲");
            }
        }
        if (target == Room.STATUS_DISABLED) {
            Long pending = bookingOrderMapper.countPendingFromToday(roomId, today);
            if (pending != null && pending > 0) {
                throw new BusinessException("该房间存在未结束的订单，不能停用，请先处理订单");
            }
        }
        room.setStatus(target);
        roomMapper.updateById(room);
        // 房间状态影响搜索可用数
        searchCacheSupport.invalidate();

        RoomVO vo = RoomVO.from(room);
        Long inHouse = bookingOrderMapper.countInHouseByRoom(roomId, today);
        vo.setOccupied(inHouse != null && inHouse > 0);
        return vo;
    }
}
