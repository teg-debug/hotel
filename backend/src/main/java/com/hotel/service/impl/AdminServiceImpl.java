package com.hotel.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hotel.common.BusinessException;
import com.hotel.common.PageResult;
import com.hotel.common.ResultCode;
import com.hotel.common.SearchCacheSupport;
import com.hotel.dto.AdminOrderQueryDTO;
import com.hotel.dto.BatchCreateRoomDTO;
import com.hotel.dto.CreateHotelDTO;
import com.hotel.dto.CreateOperatorDTO;
import com.hotel.dto.FrontDeskCreateDTO;
import com.hotel.dto.ResetPasswordDTO;
import com.hotel.dto.RoomStatusDTO;
import com.hotel.dto.RoomTypeSaveDTO;
import com.hotel.dto.UpdateHotelDTO;
import com.hotel.entity.BookingOrder;
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
import com.hotel.service.AdminService;
import com.hotel.service.OrderTxService;
import com.hotel.vo.HotelVO;
import com.hotel.vo.OperatorVO;
import com.hotel.vo.OrderVO;
import com.hotel.vo.RoomTypeVO;
import com.hotel.vo.RoomVO;
import com.hotel.vo.StatsVO;
import com.hotel.vo.UserInfoVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private static final int ROLE_OPERATOR = 1;
    private static final int ROLE_ADMIN = 2;
    private static final int ROLE_STAFF = 3;
    private static final int BATCH_ROOM_LIMIT = 100;
    private static final int MAX_PAGE_SIZE = 100;

    private final HotelMapper hotelMapper;
    private final RoomMapper roomMapper;
    private final RoomTypeMapper roomTypeMapper;
    private final BookingOrderMapper bookingOrderMapper;
    private final UserMapper userMapper;
    private final OrderTxService orderTxService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final SearchCacheSupport searchCacheSupport;

    @Override
    public List<HotelVO> listOwnHotels() {
        checkAdmin();
        return visibleHotels().stream().map(HotelVO::from).toList();
    }

    @Override
    public PageResult<OrderVO> pageOrders(AdminOrderQueryDTO dto) {
        checkAdmin();
        LambdaQueryWrapper<BookingOrder> wrapper = new LambdaQueryWrapper<>();
        // 数据隔离：经营者仅可见名下酒店订单
        if (UserContext.getRole() < ROLE_ADMIN) {
            List<Long> hotelIds = hotelMapper.selectList(new LambdaQueryWrapper<Hotel>()
                            .eq(Hotel::getOwnerId, UserContext.getUserId()))
                    .stream().map(Hotel::getId).toList();
            if (hotelIds.isEmpty()) {
                return PageResult.of(0, 0, dto.getPage(), dto.getSize(), List.of());
            }
            wrapper.in(BookingOrder::getHotelId, hotelIds);
        }
        wrapper.eq(dto.getHotelId() != null, BookingOrder::getHotelId, dto.getHotelId())
                .eq(dto.getStatus() != null, BookingOrder::getStatus, dto.getStatus())
                .orderByDesc(BookingOrder::getCreateTime);

        Page<BookingOrder> page = bookingOrderMapper.selectPage(
                new Page<>(dto.getPage(), dto.getSize()), wrapper);
        List<OrderVO> records = page.getRecords().stream().map(OrderVO::from).toList();
        return PageResult.of(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), records);
    }

    /**
     * 入住率统计。
     *
     * <p>两处口径修正：已售间夜按月份边界裁剪，跨月订单只计入落在本月的那部分；
     * 可售间夜只统计未停用的房间，停用房间不计入分母。</p>
     */
    @Override
    public List<StatsVO> occupancyStats(String month) {
        checkAdmin();
        List<Hotel> hotels = visibleHotels();

        YearMonth yearMonth = (month == null || month.isBlank())
                ? YearMonth.now() : YearMonth.parse(month);
        LocalDate start = yearMonth.atDay(1);
        LocalDate end = yearMonth.plusMonths(1).atDay(1);
        int days = (int) ChronoUnit.DAYS.between(start, end);

        List<StatsVO> result = new ArrayList<>();
        long totalCapacity = 0;
        long totalOccupied = 0;
        long totalRooms = 0;
        for (Hotel hotel : hotels) {
            Long sellableRooms = roomMapper.selectCount(new LambdaQueryWrapper<Room>()
                    .eq(Room::getHotelId, hotel.getId())
                    .ne(Room::getStatus, Room.STATUS_DISABLED));
            Long occupied = bookingOrderMapper.sumOccupiedNightsInRange(hotel.getId(), start, end);
            Long orderCount = bookingOrderMapper.countValidOrders(hotel.getId(), start, end);

            long roomsCount = sellableRooms == null ? 0 : sellableRooms;
            long capacity = roomsCount * days;
            long occupiedNights = occupied == null ? 0 : occupied;

            StatsVO vo = new StatsVO();
            vo.setHotelId(hotel.getId());
            vo.setHotelName(hotel.getName());
            vo.setTotalRooms(roomsCount);
            vo.setCapacityNights(capacity);
            vo.setOccupiedNights(occupiedNights);
            vo.setOccupancyRate(calcRate(occupiedNights, capacity));
            vo.setOrderCount(orderCount == null ? 0 : orderCount);
            result.add(vo);

            totalRooms += roomsCount;
            totalCapacity += capacity;
            totalOccupied += occupiedNights;
        }

        // 汇总行
        StatsVO summary = new StatsVO();
        summary.setHotelName("全部酒店");
        summary.setTotalRooms(totalRooms);
        summary.setCapacityNights(totalCapacity);
        summary.setOccupiedNights(totalOccupied);
        summary.setOccupancyRate(calcRate(totalOccupied, totalCapacity));
        summary.setOrderCount(result.stream().mapToLong(StatsVO::getOrderCount).sum());
        result.add(summary);
        return result;
    }

    /** 入住率 = 已售间夜 / 可售间夜 × 100%，保留 1 位小数 */
    private double calcRate(long occupiedNights, long capacityNights) {
        if (capacityNights == 0) {
            return 0;
        }
        return Math.round(occupiedNights * 1000.0 / capacityNights) / 10.0;
    }

    @Override
    public HotelVO createHotel(CreateHotelDTO dto) {
        checkAdmin();
        Long ownerId;
        if (UserContext.getRole() == ROLE_ADMIN) {
            // 管理员：可指定任意经营者（须为 role=1）
            if (dto.getOwnerId() == null) {
                throw new BusinessException("请选择酒店经营者");
            }
            User owner = userMapper.selectById(dto.getOwnerId());
            if (owner == null || !Integer.valueOf(1).equals(owner.getRole())) {
                throw new BusinessException("经营者用户不存在或不是经营者角色");
            }
            ownerId = dto.getOwnerId();
        } else {
            // 经营者：自助注册新酒店，自动归属自己
            ownerId = UserContext.getUserId();
        }
        Hotel hotel = new Hotel();
        hotel.setOwnerId(ownerId);
        hotel.setName(dto.getName());
        hotel.setCity(dto.getCity());
        hotel.setAddress(dto.getAddress());
        hotel.setStarLevel(dto.getStarLevel());
        hotel.setDescription(dto.getDescription());
        hotel.setCoverImg(dto.getCoverImg());
        hotel.setImages(dto.getImages());
        hotel.setLatitude(dto.getLatitude());
        hotel.setLongitude(dto.getLongitude());
        hotel.setPhone(dto.getPhone());
        hotel.setCheckinTime(StringUtils.hasText(dto.getCheckinTime()) ? dto.getCheckinTime() : "14:00");
        hotel.setCheckoutTime(StringUtils.hasText(dto.getCheckoutTime()) ? dto.getCheckoutTime() : "12:00");
        hotel.setStatus(1); // 新增默认营业中
        hotelMapper.insert(hotel);
        searchCacheSupport.invalidate();
        return HotelVO.from(hotel);
    }

    @Override
    public HotelVO updateHotel(Long id, UpdateHotelDTO dto) {
        checkAdmin();
        Hotel hotel = getOwnedHotel(id);
        hotel.setName(dto.getName());
        hotel.setCity(dto.getCity());
        hotel.setAddress(dto.getAddress());
        hotel.setStarLevel(dto.getStarLevel());
        hotel.setDescription(dto.getDescription());
        hotel.setCoverImg(dto.getCoverImg());
        hotel.setImages(dto.getImages());
        hotel.setLatitude(dto.getLatitude());
        hotel.setLongitude(dto.getLongitude());
        hotel.setPhone(dto.getPhone());
        hotel.setCheckinTime(dto.getCheckinTime());
        hotel.setCheckoutTime(dto.getCheckoutTime());
        // 支持上下架：不传 status 时保持原状态
        if (dto.getStatus() != null) {
            hotel.setStatus(dto.getStatus());
        }
        hotelMapper.updateById(hotel);
        // 酒店信息或营业状态变更 → 失效搜索缓存
        searchCacheSupport.invalidate();
        return HotelVO.from(hotel);
    }

    @Override
    public List<OperatorVO> listOperators() {
        checkSysAdmin();
        List<User> operators = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getRole, 1)
                .eq(User::getStatus, 1)
                .orderByAsc(User::getId));
        return operators.stream().map(OperatorVO::from).toList();
    }

    @Override
    public OperatorVO createOperator(CreateOperatorDTO dto) {
        checkSysAdmin();
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, dto.getUsername()));
        if (count > 0) {
            throw new BusinessException("用户名已存在");
        }
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword())); // BCrypt 加密
        user.setNickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname() : dto.getUsername());
        user.setPhone(dto.getPhone());
        user.setMemberLevel(0);
        user.setRole(ROLE_OPERATOR); // 酒店经营者
        user.setStatus(1);
        userMapper.insert(user);
        return OperatorVO.from(user);
    }

    /**
     * 用户分页查询。
     *
     * <p>返回体包含手机号等个人信息，原先的全量返回会随用户规模线性增长，
     * 因此改为分页并限制单页上限。</p>
     */
    @Override
    public PageResult<UserInfoVO> pageUsers(int page, int size) {
        checkSysAdmin();
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<User> p = userMapper.selectPage(new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<User>().orderByAsc(User::getId));
        List<UserInfoVO> records = p.getRecords().stream().map(UserInfoVO::from).toList();
        return PageResult.of(p.getTotal(), p.getPages(), p.getCurrent(), p.getSize(), records);
    }

    @Override
    public void resetPassword(Long userId, ResetPasswordDTO dto) {
        checkSysAdmin();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        applyPassword(user, dto.getNewPassword());
        userMapper.updateById(user);
    }

    @Override
    public void resetStaffPassword(Long hotelId, Long staffId, ResetPasswordDTO dto) {
        getOwnedHotel(hotelId); // 归属校验：经营者仅可重置自己酒店的前台密码
        User staff = userMapper.selectById(staffId);
        if (staff == null || !Integer.valueOf(ROLE_STAFF).equals(staff.getRole())
                || !hotelId.equals(staff.getHotelId())) {
            throw new BusinessException("前台账号不存在或不属于该酒店");
        }
        applyPassword(staff, dto.getNewPassword());
        userMapper.updateById(staff);
    }

    /** 更新密码：BCrypt 哈希存储，不保留明文 */
    private void applyPassword(User user, String rawPassword) {
        user.setPassword(passwordEncoder.encode(rawPassword));
    }

    @Override
    public List<OperatorVO> listHotelStaff(Long hotelId) {
        getOwnedHotel(hotelId);
        List<User> staff = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getRole, ROLE_STAFF)
                .eq(User::getHotelId, hotelId)
                .eq(User::getStatus, 1)
                .orderByAsc(User::getId));
        return staff.stream().map(OperatorVO::from).toList();
    }

    @Override
    public OperatorVO createHotelStaff(Long hotelId, FrontDeskCreateDTO dto) {
        getOwnedHotel(hotelId); // 校验酒店归属（经营者仅可给自己酒店下设前台）
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, dto.getUsername()));
        if (count > 0) {
            throw new BusinessException("用户名已存在");
        }
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword())); // BCrypt 加密
        user.setNickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname() : dto.getUsername());
        user.setPhone(dto.getPhone());
        user.setMemberLevel(0);
        user.setRole(ROLE_STAFF);   // 前台账号
        user.setHotelId(hotelId);   // 绑定酒店
        user.setStatus(1);
        userMapper.insert(user);
        return OperatorVO.from(user);
    }

    // ------------------------------------------------------------------
    // 房型管理
    // ------------------------------------------------------------------

    @Override
    public List<RoomTypeVO> listRoomTypes(Long hotelId) {
        getOwnedHotel(hotelId);
        List<RoomType> types = roomTypeMapper.selectList(new LambdaQueryWrapper<RoomType>()
                .eq(RoomType::getHotelId, hotelId)
                .orderByAsc(RoomType::getPrice));
        return types.stream().map(rt -> {
            RoomTypeVO vo = RoomTypeVO.from(rt);
            vo.setRoomCount(roomMapper.selectCount(new LambdaQueryWrapper<Room>()
                    .eq(Room::getHotelId, hotelId)
                    .eq(Room::getRoomTypeId, rt.getId())));
            return vo;
        }).toList();
    }

    @Override
    public RoomTypeVO createRoomType(RoomTypeSaveDTO dto) {
        getOwnedHotel(dto.getHotelId());
        RoomType type = new RoomType();
        type.setHotelId(dto.getHotelId());
        type.setName(dto.getName());
        type.setBedType(dto.getBedType());
        type.setArea(dto.getArea());
        type.setMaxGuests(dto.getMaxGuests() == null ? 2 : dto.getMaxGuests());
        type.setPrice(dto.getPrice());
        type.setBreakfast(dto.getBreakfast() == null ? 0 : dto.getBreakfast());
        type.setImgUrl(dto.getImgUrl());
        type.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        roomTypeMapper.insert(type);
        searchCacheSupport.invalidate();
        return RoomTypeVO.from(type);
    }

    @Override
    public RoomTypeVO updateRoomType(Long id, RoomTypeSaveDTO dto) {
        RoomType type = getOwnedRoomType(id);
        type.setName(dto.getName());
        type.setBedType(dto.getBedType());
        type.setArea(dto.getArea());
        type.setMaxGuests(dto.getMaxGuests() == null ? 2 : dto.getMaxGuests());
        type.setPrice(dto.getPrice());
        type.setBreakfast(dto.getBreakfast() == null ? 0 : dto.getBreakfast());
        type.setImgUrl(dto.getImgUrl());
        type.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        roomTypeMapper.updateById(type);
        searchCacheSupport.invalidate();
        return RoomTypeVO.from(type);
    }

    @Override
    public void deleteRoomType(Long id) {
        RoomType type = getOwnedRoomType(id);
        Long roomCount = roomMapper.selectCount(new LambdaQueryWrapper<Room>()
                .eq(Room::getHotelId, type.getHotelId())
                .eq(Room::getRoomTypeId, id));
        if (roomCount != null && roomCount > 0) {
            throw new BusinessException("该房型下仍有房间，请先删除房间");
        }
        roomTypeMapper.deleteById(id);
        searchCacheSupport.invalidate();
    }

    // ------------------------------------------------------------------
    // 房间管理
    // ------------------------------------------------------------------

    @Override
    public List<RoomVO> listRooms(Long roomTypeId) {
        RoomType type = getOwnedRoomType(roomTypeId);
        List<Room> rooms = roomMapper.selectList(new LambdaQueryWrapper<Room>()
                .eq(Room::getHotelId, type.getHotelId())
                .eq(Room::getRoomTypeId, roomTypeId)
                .orderByAsc(Room::getRoomNo));
        return toRoomVOs(rooms);
    }

    @Override
    public List<RoomVO> batchCreateRooms(Long roomTypeId, BatchCreateRoomDTO dto) {
        RoomType type = getOwnedRoomType(roomTypeId);
        if (dto.getStartNo() > dto.getEndNo()) {
            throw new BusinessException("起始编号不能大于结束编号");
        }
        int count = dto.getEndNo() - dto.getStartNo() + 1;
        if (count > BATCH_ROOM_LIMIT) {
            throw new BusinessException("单次最多生成 " + BATCH_ROOM_LIMIT + " 个房间");
        }
        // 该酒店已存在的房间号（跨房型去重，避免撞唯一键 uk_hotel_room_no）
        Set<String> existing = roomMapper.selectList(new LambdaQueryWrapper<Room>()
                        .eq(Room::getHotelId, type.getHotelId()))
                .stream().map(Room::getRoomNo).collect(Collectors.toSet());

        List<Room> rooms = new ArrayList<>();
        for (int n = dto.getStartNo(); n <= dto.getEndNo(); n++) {
            String roomNo = String.valueOf(dto.getFloor() * 100 + n);
            if (existing.contains(roomNo)) {
                continue;
            }
            Room room = new Room();
            room.setHotelId(type.getHotelId());
            room.setRoomTypeId(roomTypeId);
            room.setRoomNo(roomNo);
            room.setFloor(dto.getFloor());
            room.setStatus(Room.STATUS_IDLE);
            rooms.add(room);
        }
        if (rooms.isEmpty()) {
            throw new BusinessException("该编号段的房间已全部存在");
        }
        roomMapper.insert(rooms); // MyBatis-Plus 批量插入
        searchCacheSupport.invalidate();
        return rooms.stream().map(RoomVO::from).toList();
    }

    /**
     * 修改房间状态。
     *
     * <p>房间状态只表示物理与运营状态，因此校验规则围绕「改动是否会与既有订单冲突」展开：
     * 有客在住时不允许置为空闲；存在未结束的订单时不允许停用。</p>
     */
    @Override
    public RoomVO updateRoomStatus(Long roomId, RoomStatusDTO dto) {
        Room room = getOwnedRoom(roomId);
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
        searchCacheSupport.invalidate();
        return RoomVO.from(room);
    }

    @Override
    public void deleteRoom(Long roomId) {
        Room room = getOwnedRoom(roomId);
        Long activeOrders = bookingOrderMapper.countActiveByRoom(roomId);
        if (activeOrders != null && activeOrders > 0) {
            throw new BusinessException("该房间存在有效订单，无法删除");
        }
        roomMapper.deleteById(roomId);
        searchCacheSupport.invalidate();
    }

    // ------------------------------------------------------------------
    // 入住 / 退房
    // ------------------------------------------------------------------

    @Override
    public OrderVO checkIn(String orderNo) {
        BookingOrder order = getOwnedOrder(orderNo);
        orderTxService.checkIn(order);
        return OrderVO.from(bookingOrderMapper.selectById(order.getId()));
    }

    @Override
    public OrderVO checkOut(String orderNo) {
        BookingOrder order = getOwnedOrder(orderNo);
        orderTxService.checkOut(order);
        return OrderVO.from(bookingOrderMapper.selectById(order.getId()));
    }

    /** 校验订单归属：经营者仅可操作名下酒店的订单 */
    private BookingOrder getOwnedOrder(String orderNo) {
        checkAdmin();
        BookingOrder order = bookingOrderMapper.selectOne(
                new LambdaQueryWrapper<BookingOrder>().eq(BookingOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        getOwnedHotel(order.getHotelId());
        return order;
    }

    // ------------------------------------------------------------------
    // 权限与归属校验
    // ------------------------------------------------------------------

    /** 当前角色可见的酒店集合 */
    private List<Hotel> visibleHotels() {
        if (UserContext.getRole() >= ROLE_ADMIN) {
            return hotelMapper.selectList(new LambdaQueryWrapper<Hotel>().orderByAsc(Hotel::getId));
        }
        return hotelMapper.selectList(new LambdaQueryWrapper<Hotel>()
                .eq(Hotel::getOwnerId, UserContext.getUserId())
                .orderByAsc(Hotel::getId));
    }

    /** 批量补全房间的在住标记（一次查询完成，避免逐间查询） */
    private List<RoomVO> toRoomVOs(List<Room> rooms) {
        List<RoomVO> vos = rooms.stream().map(RoomVO::from).toList();
        if (rooms.isEmpty()) {
            return vos;
        }
        List<Long> roomIds = rooms.stream().map(Room::getId).toList();
        List<Long> inHouseIds = bookingOrderMapper.selectInHouseRoomIds(roomIds, LocalDate.now());
        if (inHouseIds == null || inHouseIds.isEmpty()) {
            return vos;
        }
        Set<Long> inHouseSet = new HashSet<>(inHouseIds);
        vos.forEach(vo -> vo.setOccupied(inHouseSet.contains(vo.getId())));
        return vos;
    }

    /** 校验酒店归属：经营者仅可操作名下酒店 */
    private Hotel getOwnedHotel(Long hotelId) {
        checkAdmin();
        Hotel hotel = hotelMapper.selectById(hotelId);
        if (hotel == null) {
            throw new BusinessException("酒店不存在");
        }
        if (UserContext.getRole() < ROLE_ADMIN && !hotel.getOwnerId().equals(UserContext.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该酒店");
        }
        return hotel;
    }

    /** 校验房型归属 */
    private RoomType getOwnedRoomType(Long roomTypeId) {
        checkAdmin();
        RoomType type = roomTypeMapper.selectById(roomTypeId);
        if (type == null) {
            throw new BusinessException("房型不存在");
        }
        getOwnedHotel(type.getHotelId());
        return type;
    }

    /** 校验房间归属 */
    private Room getOwnedRoom(Long roomId) {
        checkAdmin();
        Room room = roomMapper.selectById(roomId);
        if (room == null) {
            throw new BusinessException("房间不存在");
        }
        getOwnedHotel(room.getHotelId());
        return room;
    }

    private void checkSysAdmin() {
        Integer role = UserContext.getRole();
        if (role == null || role != ROLE_ADMIN) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅系统管理员可操作");
        }
    }

    private void checkAdmin() {
        Integer role = UserContext.getRole();
        if (role == null || !(role == ROLE_OPERATOR || role == ROLE_ADMIN)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权限访问");
        }
    }
}
