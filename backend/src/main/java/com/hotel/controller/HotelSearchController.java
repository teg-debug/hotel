package com.hotel.controller;

import com.hotel.common.RawJson;
import com.hotel.common.Result;
import com.hotel.dto.HotelSearchDTO;
import com.hotel.service.HotelSearchService;
import com.hotel.vo.RoomVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 房源搜索：分页 + 城市/星级/关键词 + 日期冲突检查，Redis 缓存 5 分钟
 */
@RestController
@RequestMapping("/api/v1/hotels")
@RequiredArgsConstructor
public class HotelSearchController {

    private final HotelSearchService hotelSearchService;

    @GetMapping("/search")
    public Result<RawJson> search(@Valid HotelSearchDTO dto) {
        // 服务端缓存里存的就是结果 JSON，这里包一层信封即可：命中的请求不再经过
        // 反序列化 + 重新序列化，响应结构对外完全不变
        return Result.success(new RawJson(hotelSearchService.searchJson(dto)));
    }

    /** 指定酒店/房型在日期区间内的可用房间列表（供下单前挑选房间） */
    @GetMapping("/{hotelId}/room-types/{roomTypeId}/available-rooms")
    public Result<List<RoomVO>> availableRooms(@PathVariable Long hotelId,
                                               @PathVariable Long roomTypeId,
                                               @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate checkin,
                                               @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate checkout) {
        return Result.success(hotelSearchService.availableRooms(hotelId, roomTypeId, checkin, checkout));
    }
}
