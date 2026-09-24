package com.hotel.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.common.BusinessException;
import com.hotel.common.PageResult;
import com.hotel.common.SearchCacheSupport;
import com.hotel.dto.HotelSearchDTO;
import com.hotel.entity.Hotel;
import com.hotel.entity.Room;
import com.hotel.entity.RoomType;
import com.hotel.mapper.BookingOrderMapper;
import com.hotel.mapper.HotelMapper;
import com.hotel.mapper.RoomMapper;
import com.hotel.mapper.RoomTypeMapper;
import com.hotel.service.HotelSearchService;
import com.hotel.vo.HotelSearchVO;
import com.hotel.vo.RoomTypeSearchVO;
import com.hotel.vo.RoomVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 房源搜索：动态条件分页 + 日期冲突检查 + Redis 5 分钟缓存。
 *
 * <p>回源路径做了三件事，前两件用于顶住「缓存被持续失效」时的流量，
 * 第三件用于压低接口层的固定开销：</p>
 *
 * <ol>
 *   <li><b>单飞（single-flight）</b>：同一个缓存键只放一个请求去查库，其余请求等它的结果。
 *       缓存版本号一旦自增，全部搜索键同时作废，若没有单飞，并发的一批请求会一起落到数据库
 *       （缓存击穿），把数据库打成串行瓶颈；</li>
 *   <li><b>一次成型（消掉 N+1）</b>：原先每家酒店查一次房型、每个房型查一次可售数，
 *       一页 10 家酒店要 31 条 SQL；现在分页 + 房型 + 可售数各 1 条，共 3 条；</li>
 *   <li><b>缓存存「已序列化的结果 JSON」</b>：命中时直接把这段 JSON 交给 HTTP 层嵌入响应体，
 *       既不反序列化成对象、也不由 Jackson 再走一遍对象图。搜索响应是几 KB 的酒店列表，
 *       这两次遍历在接口层实测占了固定开销的相当一部分。</li>
 * </ol>
 *
 * <p>对象形态的 {@link #search} 仍然保留（AI 工具等调用方需要结构化结果），
 * 它会在命中缓存时多一次反序列化——这是有意为之：HTTP 热路径与对象调用方各自走最省的路径。</p>
 *
 * <p>单飞的作用域是<b>单个 JVM</b>：多实例部署时每个实例各放一个请求过去，
 * 数据库压力与实例数成正比而不再与并发请求数成正比。跨实例单飞需要分布式锁，
 * 代价是把 Redis 往返压到回源路径上，收益（相对本地单飞）有限，故未引入。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotelSearchServiceImpl implements HotelSearchService {

    private static final long CACHE_TTL_MINUTES = 5;

    /**
     * 缓存键前缀带 {@code payload} 段：缓存内容的形态从「序列化对象」换成了「结果 JSON」，
     * 换个前缀可以让部署时残留的旧格式键自然过期，不会被新代码读到。
     */
    private static final String CACHE_PREFIX = "hotel:search:payload:";

    /** 单飞时等待领跑者结果的上限；超时就退化为自己查库，避免请求被拖死 */
    private static final long SINGLE_FLIGHT_WAIT_MILLIS = 2000;

    private final HotelMapper hotelMapper;
    private final RoomTypeMapper roomTypeMapper;
    private final RoomMapper roomMapper;
    private final BookingOrderMapper bookingOrderMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final SearchCacheSupport searchCacheSupport;
    private final ObjectMapper objectMapper;

    /** 正在回源的缓存键 → 领跑者算出的结果 JSON；键随缓存版本变化，失效后自然换组 */
    private final ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

    @Override
    public PageResult<HotelSearchVO> search(HotelSearchDTO dto) {
        return toPageResult(searchJson(dto));
    }

    @Override
    public String searchJson(HotelSearchDTO dto) {
        String cacheKey = buildCacheKey(dto);

        // 1. 读缓存：命中的是一段现成的 JSON，直接返回，不做任何对象转换
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 2. 单飞：抢到「回源权」的请求负责查库、序列化并回填缓存，其余请求等它的结果
        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> leader = inFlight.putIfAbsent(cacheKey, mine);
        if (leader != null) {
            return awaitLeader(leader, dto, cacheKey);
        }
        try {
            String json = serialize(loadFromDatabase(dto));
            stringRedisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            mine.complete(json);
            return json;
        } catch (RuntimeException e) {
            // 让等待者尽快失败并退化为自己查库，而不是跟着一起挂住
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(cacheKey, mine);
        }
    }

    @Override
    public List<RoomVO> availableRooms(Long hotelId, Long roomTypeId, LocalDate checkin, LocalDate checkout) {
        Hotel hotel = hotelMapper.selectById(hotelId);
        if (hotel == null || hotel.getStatus() == 0) {
            throw new BusinessException("酒店不存在或已下架");
        }
        RoomType roomType = roomTypeMapper.selectById(roomTypeId);
        if (roomType == null || !roomType.getHotelId().equals(hotelId)) {
            throw new BusinessException("房型不存在");
        }
        if (checkin == null || checkout == null || !checkin.isBefore(checkout)) {
            throw new BusinessException("入住日期必须在离店日期之前");
        }
        return roomMapper.selectAvailableRooms(hotelId, roomTypeId, checkin, checkout)
                .stream().map(RoomVO::from).toList();
    }

    // ===================== 结果 JSON 与单飞等待 =====================

    private String serialize(PageResult<HotelSearchVO> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 结果无法序列化属于服务端缺陷，不应伪装成业务错误
            throw new IllegalStateException("搜索结果序列化失败", e);
        }
    }

    private PageResult<HotelSearchVO> toPageResult(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<PageResult<HotelSearchVO>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("搜索结果反序列化失败", e);
        }
    }

    /**
     * 等待领跑者的结果；等待失败（领跑者报错或超时）则退化为自己查库。
     *
     * <p>退化路径不再回填缓存，避免在异常风暴里放大写放大；此时数据库压力会上升，
     * 但至少不会出现「一个请求失败带走一整批」的连锁反应。</p>
     */
    private String awaitLeader(CompletableFuture<String> leader, HotelSearchDTO dto, String cacheKey) {
        try {
            return leader.get(SINGLE_FLIGHT_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("系统繁忙，请稍后重试");
        } catch (ExecutionException | TimeoutException e) {
            log.warn("缓存回源等待失败，退化为直接查库 cacheKey={} 原因={}", cacheKey, e.toString());
            return serialize(loadFromDatabase(dto));
        }
    }

    // ===================== 回源：一次成型 =====================

    private PageResult<HotelSearchVO> loadFromDatabase(HotelSearchDTO dto) {
        String city = escapeLike(dto.getCity());
        String keyword = escapeLike(dto.getKeyword());
        LambdaQueryWrapper<Hotel> wrapper = new LambdaQueryWrapper<Hotel>()
                .like(StringUtils.hasText(city), Hotel::getCity, city)
                .eq(dto.getStarLevel() != null, Hotel::getStarLevel, dto.getStarLevel())
                .and(StringUtils.hasText(keyword),
                        w -> w.like(Hotel::getName, keyword)
                                .or().like(Hotel::getAddress, keyword))
                .eq(Hotel::getStatus, 1)
                .orderByDesc(Hotel::getStarLevel)
                .orderByAsc(Hotel::getId);
        Page<Hotel> page = hotelMapper.selectPage(new Page<>(dto.getPage(), dto.getSize()), wrapper);

        List<Hotel> hotels = page.getRecords();
        List<HotelSearchVO> records = hotels.isEmpty()
                ? List.of()
                : assemble(hotels, dto.getCheckin(), dto.getCheckout());
        return PageResult.of(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), records);
    }

    /**
     * 组装整页酒店：房型与可售数各用一条批量查询取回，再在内存里按酒店归组。
     *
     * <p>全程只有 3 条 SQL：分页查酒店 / 批量查房型 / 批量查可售数。</p>
     */
    private List<HotelSearchVO> assemble(List<Hotel> hotels, LocalDate checkin, LocalDate checkout) {
        List<Long> hotelIds = hotels.stream().map(Hotel::getId).toList();

        List<RoomType> roomTypes = roomTypeMapper.selectList(new LambdaQueryWrapper<RoomType>()
                .in(RoomType::getHotelId, hotelIds)
                .eq(RoomType::getStatus, 1)
                .orderByAsc(RoomType::getPrice));
        Map<Long, List<RoomType>> roomTypesByHotel = roomTypes.stream()
                .collect(Collectors.groupingBy(RoomType::getHotelId, LinkedHashMap::new, Collectors.toList()));

        Map<Long, Long> availableByRoomType = availableCountByRoomType(roomTypes, checkin, checkout);

        List<HotelSearchVO> records = new ArrayList<>(hotels.size());
        for (Hotel hotel : hotels) {
            HotelSearchVO vo = buildHotelVO(hotel,
                    roomTypesByHotel.getOrDefault(hotel.getId(), List.of()), availableByRoomType);
            if (vo != null) {
                records.add(vo);
            }
        }
        return records;
    }

    /** 可售数统计：带日期时按区间冲突算，不带日期时只看房间物理状态 */
    private Map<Long, Long> availableCountByRoomType(List<RoomType> roomTypes,
                                                     LocalDate checkin, LocalDate checkout) {
        if (roomTypes.isEmpty()) {
            return Map.of();
        }
        List<Long> hotelIds = roomTypes.stream().map(RoomType::getHotelId).distinct().toList();
        List<Long> roomTypeIds = roomTypes.stream().map(RoomType::getId).toList();
        boolean withDates = checkin != null && checkout != null;

        List<Map<String, Object>> rows = withDates
                ? bookingOrderMapper.countAvailableRoomsBatch(hotelIds, roomTypeIds, checkin, checkout)
                : roomMapper.countIdleRoomsBatch(hotelIds, roomTypeIds);

        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put(toLong(row.get("roomTypeId")), toLong(row.get("availableCount")));
        }
        return counts;
    }

    private long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 组装单个酒店的搜索 VO。
     *
     * <p>可用数由批量查询在内存里按房型归组得到，语义与原先一致：
     * 「空闲且区间内无订单」的房间数为 0 的房型不展示。</p>
     */
    private HotelSearchVO buildHotelVO(Hotel hotel, List<RoomType> roomTypes,
                                       Map<Long, Long> availableByRoomType) {
        List<RoomTypeSearchVO> roomTypeVOs = new ArrayList<>();
        BigDecimal lowestPrice = null;
        for (RoomType roomType : roomTypes) {
            long available = availableByRoomType.getOrDefault(roomType.getId(), 0L);
            if (available <= 0) {
                continue;
            }
            RoomTypeSearchVO vo = new RoomTypeSearchVO();
            vo.setId(roomType.getId());
            vo.setName(roomType.getName());
            vo.setBedType(roomType.getBedType());
            vo.setMaxGuests(roomType.getMaxGuests());
            vo.setBreakfast(roomType.getBreakfast());
            vo.setPrice(roomType.getPrice());
            vo.setImgUrl(roomType.getImgUrl());
            vo.setAvailableCount((int) available);
            roomTypeVOs.add(vo);
            if (lowestPrice == null || roomType.getPrice().compareTo(lowestPrice) < 0) {
                lowestPrice = roomType.getPrice();
            }
        }
        if (roomTypeVOs.isEmpty()) {
            return null;
        }
        HotelSearchVO vo = new HotelSearchVO();
        vo.setHotelId(hotel.getId());
        vo.setHotelName(hotel.getName());
        vo.setCity(hotel.getCity());
        vo.setAddress(hotel.getAddress());
        vo.setStarLevel(hotel.getStarLevel());
        vo.setDescription(hotel.getDescription());
        vo.setCoverImg(hotel.getCoverImg());
        vo.setImages(hotel.getImages());
        vo.setLatitude(hotel.getLatitude());
        vo.setLongitude(hotel.getLongitude());
        vo.setLowestPrice(lowestPrice);
        vo.setAvailableRoomTypes(roomTypeVOs);
        return vo;
    }

    /**
     * 缓存键 = 版本号 + 入参摘要。
     *
     * <p>版本号由失效操作自增，因此不需要按前缀删除键；
     * 入参先做摘要，避免原始值里的分隔符造成键冲突（如 city="a:1"）。</p>
     */
    private String buildCacheKey(HotelSearchDTO dto) {
        String raw = String.join("|",
                nvl(dto.getCity()),
                nvl(dto.getStarLevel()),
                nvl(dto.getKeyword()),
                nvl(dto.getCheckin()),
                nvl(dto.getCheckout()),
                String.valueOf(dto.getPage()),
                String.valueOf(dto.getSize()));
        String digest = DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
        return CACHE_PREFIX + "v" + searchCacheSupport.currentVersion() + ":" + digest;
    }

    /** 转义 LIKE 通配符，避免用户输入 % 或 _ 触发全表匹配 */
    private String escapeLike(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String nvl(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
