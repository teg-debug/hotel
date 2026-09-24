package com.hotel.booking;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hotel.common.PageResult;
import com.hotel.common.SearchCacheSupport;
import com.hotel.dto.HotelSearchDTO;
import com.hotel.entity.Hotel;
import com.hotel.entity.Room;
import com.hotel.entity.RoomType;
import com.hotel.entity.User;
import com.hotel.service.HotelSearchService;
import com.hotel.vo.HotelSearchVO;
import com.hotel.vo.RoomTypeSearchVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 搜索页可售数统计的行为校验。
 *
 * <p>可售数原来由「逐房型查一次 COUNT」得出，为了消掉 N+1 改成了批量分组查询 + 内存归组。
 * 这类改动的风险是「吞吐上去了、数字悄悄错了」（分组键对不上、状态过滤漏了、日期冲突没算），
 * 而基准测试只测吞吐，抓不到这种错，所以这里按值校验：</p>
 *
 * <ul>
 *   <li>带日期：可售数 = 空闲且区间内没有有效订单的房间数；</li>
 *   <li>不带日期：可售数只看房间物理状态，已订房间不影响（与原实现语义一致）；</li>
 *   <li>某酒店所有在售房型的可售数都为 0 时，该酒店不出现在搜索结果里。</li>
 * </ul>
 */
@DisplayName("搜索页可售数统计")
class SearchAvailabilityQueryTest extends BookingIntegrationTestSupport {

    private static final String CITY = "可售数校验城";

    @Autowired
    private HotelSearchService hotelSearchService;

    @Autowired
    private SearchCacheSupport searchCacheSupport;

    @Test
    @DisplayName("带日期搜索：可售数扣掉区间内已下单的房间")
    void availableCountDeductsRoomsBookedInRange() {
        List<Long> hotelIds = seedHotels(CITY, 2, 2, 3);
        Long firstHotel = hotelIds.get(0);
        Long secondHotel = hotelIds.get(1);
        List<Long> firstHotelTypes = roomTypeIdsOf(firstHotel);
        List<Long> firstHotelRooms = roomIdsOf(firstHotel, firstHotelTypes.get(0));

        LocalDate checkin = LocalDate.now().plusDays(11);
        LocalDate checkout = checkin.plusDays(2);

        // 类型1 订掉 1 间、类型2 订掉 2 间；另一家酒店完全不动
        bookRoom(firstHotel, firstHotelTypes.get(0), firstHotelRooms.get(0), checkin, checkout);
        List<Long> type2Rooms = roomIdsOf(firstHotel, firstHotelTypes.get(1));
        bookRoom(firstHotel, firstHotelTypes.get(1), type2Rooms.get(0), checkin, checkout);
        bookRoom(firstHotel, firstHotelTypes.get(1), type2Rooms.get(1), checkin, checkout);

        HotelSearchVO hotel = searchHotel(CITY, checkin, checkout, firstHotel);
        assertThat(availableOf(hotel, firstHotelTypes.get(0))).isEqualTo(2);
        assertThat(availableOf(hotel, firstHotelTypes.get(1))).isEqualTo(1);

        HotelSearchVO untouched = searchHotel(CITY, checkin, checkout, secondHotel);
        for (Long roomTypeId : roomTypeIdsOf(secondHotel)) {
            assertThat(availableOf(untouched, roomTypeId)).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("不带日期搜索：只看房间物理状态，已订房间不算减少")
    void availableCountWithoutDatesIgnoresOrdersButHonoursRoomStatus() {
        List<Long> hotelIds = seedHotels(CITY, 1, 1, 3);
        Long hotelId = hotelIds.get(0);
        Long roomTypeId = roomTypeIdsOf(hotelId).get(0);
        List<Long> rooms = roomIdsOf(hotelId, roomTypeId);

        LocalDate checkin = LocalDate.now().plusDays(21);
        LocalDate checkout = checkin.plusDays(1);
        bookRoom(hotelId, roomTypeId, rooms.get(0), checkin, checkout);

        assertThat(availableOf(searchHotel(CITY, null, null, hotelId), roomTypeId))
                .as("不带日期时按原语义只看物理状态，已订房间仍计入")
                .isEqualTo(3);
        assertThat(availableOf(searchHotel(CITY, checkin, checkout, hotelId), roomTypeId))
                .as("带日期时该房间因区间冲突被扣除")
                .isEqualTo(2);

        // 把一间空闲房置为「打扫中」，不带日期的统计应把它排除
        roomMapper.update(null, new LambdaUpdateWrapper<Room>()
                .eq(Room::getId, rooms.get(1))
                .set(Room::getStatus, Room.STATUS_CLEANING));
        // 这里直接改了库（绕过了服务层），所以补一次缓存失效：
        // 生产里房态变更会由 AdminServiceImpl / StaffServiceImpl 调用 invalidate()
        searchCacheSupport.invalidate();
        assertThat(availableOf(searchHotel(CITY, null, null, hotelId), roomTypeId))
                .as("非空闲房间不计入可售数")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("所有在售房型都无房可售的酒店不出现在结果里")
    void hotelWithNoAvailableRoomTypeIsFilteredOut() {
        List<Long> hotelIds = seedHotels(CITY, 2, 1, 1);
        Long fullHotel = hotelIds.get(0);
        Long availableHotel = hotelIds.get(1);

        LocalDate checkin = LocalDate.now().plusDays(31);
        LocalDate checkout = checkin.plusDays(1);
        // 把第一家酒店唯一一间房订掉
        bookRoom(fullHotel, roomTypeIdsOf(fullHotel).get(0), roomIdsOf(fullHotel, roomTypeIdsOf(fullHotel).get(0)).get(0),
                checkin, checkout);

        List<Long> visibleHotelIds = search(CITY, checkin, checkout).getRecords().stream()
                .map(HotelSearchVO::getHotelId).toList();
        assertThat(visibleHotelIds)
                .as("无房可售的酒店应被过滤掉")
                .doesNotContain(fullHotel)
                .contains(availableHotel);
    }

    // ===================== 夹具与小工具 =====================

    /** 造一批同城酒店：每店 roomTypesPerHotel 个在售房型、每房型 roomsPerRoomType 间空闲房 */
    private List<Long> seedHotels(String city, int hotelCount, int roomTypesPerHotel, int roomsPerRoomType) {
        User owner = new User();
        owner.setUsername("avail-owner-" + System.nanoTime());
        owner.setPassword("$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG");
        owner.setNickname("可售数校验经营者");
        owner.setMemberLevel(0);
        owner.setRole(1);
        owner.setStatus(1);
        userMapper.insert(owner);

        List<Long> hotelIds = new ArrayList<>(hotelCount);
        for (int h = 0; h < hotelCount; h++) {
            Hotel hotel = new Hotel();
            hotel.setOwnerId(owner.getId());
            hotel.setName("可售数校验酒店-" + h + "-" + System.nanoTime());
            hotel.setCity(city);
            hotel.setAddress("可售数校验路 " + h + " 号");
            hotel.setStarLevel(4);
            hotel.setStatus(1);
            hotelMapper.insert(hotel);

            for (int t = 0; t < roomTypesPerHotel; t++) {
                RoomType roomType = new RoomType();
                roomType.setHotelId(hotel.getId());
                roomType.setName("可售数校验房型" + t);
                roomType.setBedType("大床");
                roomType.setArea(30);
                roomType.setMaxGuests(2);
                roomType.setPrice(new BigDecimal(400 + t * 100L));
                roomType.setBreakfast(0);
                roomType.setStatus(1);
                roomTypeMapper.insert(roomType);

                for (int r = 0; r < roomsPerRoomType; r++) {
                    Room room = new Room();
                    room.setHotelId(hotel.getId());
                    room.setRoomTypeId(roomType.getId());
                    room.setRoomNo("A" + t + (100 + r));
                    room.setFloor(1);
                    room.setStatus(Room.STATUS_IDLE);
                    roomMapper.insert(room);
                }
            }
            hotelIds.add(hotel.getId());
        }
        return hotelIds;
    }

    private List<Long> roomTypeIdsOf(Long hotelId) {
        return roomTypeMapper.selectList(new LambdaQueryWrapper<RoomType>()
                        .eq(RoomType::getHotelId, hotelId)
                        .orderByAsc(RoomType::getPrice))
                .stream().map(RoomType::getId).toList();
    }

    private List<Long> roomIdsOf(Long hotelId, Long roomTypeId) {
        return roomMapper.selectList(new LambdaQueryWrapper<Room>()
                        .eq(Room::getHotelId, hotelId)
                        .eq(Room::getRoomTypeId, roomTypeId)
                        .orderByAsc(Room::getId))
                .stream().map(Room::getId).toList();
    }

    /** 直接落一条有效订单，占住该房间在区间内的库存 */
    private void bookRoom(Long hotelId, Long roomTypeId, Long roomId, LocalDate checkin, LocalDate checkout) {
        Fixture fixture = new Fixture(hotelId, roomTypeId, guestUserId(), List.of(roomId), List.of("A100"), "avail-check");
        insertOrderDirectly(fixture, roomId, checkin, checkout);
    }

    private Long guestUserId() {
        User guest = new User();
        guest.setUsername("avail-guest-" + System.nanoTime());
        guest.setPassword("$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG");
        guest.setNickname("可售数校验下单人");
        guest.setMemberLevel(0);
        guest.setRole(0);
        guest.setStatus(1);
        userMapper.insert(guest);
        return guest.getId();
    }

    private PageResult<HotelSearchVO> search(String city, LocalDate checkin, LocalDate checkout) {
        HotelSearchDTO dto = new HotelSearchDTO();
        dto.setCity(city);
        dto.setPage(1);
        dto.setSize(20);
        dto.setCheckin(checkin);
        dto.setCheckout(checkout);
        return hotelSearchService.search(dto);
    }

    private HotelSearchVO searchHotel(String city, LocalDate checkin, LocalDate checkout, Long hotelId) {
        return search(city, checkin, checkout).getRecords().stream()
                .filter(vo -> hotelId.equals(vo.getHotelId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("搜索结果里没有酒店 " + hotelId));
    }

    private int availableOf(HotelSearchVO hotel, Long roomTypeId) {
        return hotel.getAvailableRoomTypes().stream()
                .filter(vo -> roomTypeId.equals(vo.getId()))
                .map(RoomTypeSearchVO::getAvailableCount)
                .findFirst()
                .orElse(0);
    }
}
