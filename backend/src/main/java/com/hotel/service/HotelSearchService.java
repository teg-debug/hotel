package com.hotel.service;

import com.hotel.common.PageResult;
import com.hotel.dto.HotelSearchDTO;
import com.hotel.vo.HotelSearchVO;
import com.hotel.vo.RoomVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 房源搜索：动态条件分页 + 日期冲突检查 + Redis 5 分钟缓存
 */
public interface HotelSearchService {

    /**
     * 房源搜索（对象形态）：供需要拿到结构化结果的调用方使用，例如 AI 工具。
     *
     * <p>命中缓存时会把缓存的 JSON 反序列化成对象，因此比 {@link #searchJson} 多一次解析；
     * HTTP 接口请直接用 {@link #searchJson}。</p>
     */
    PageResult<HotelSearchVO> search(HotelSearchDTO dto);

    /**
     * 房源搜索（JSON 形态）：返回<b>已序列化的结果数据</b>，供 HTTP 层直接嵌入响应体。
     *
     * <p>命中缓存时不反序列化、不重新序列化——缓存里存的就是这段 JSON，
     * 这是把「接口层固定开销」里最贵的两次对象图遍历去掉的关键。</p>
     */
    String searchJson(HotelSearchDTO dto);

    /** 查询指定酒店/房型在日期区间内的全部可用房间（供用户挑选） */
    List<RoomVO> availableRooms(Long hotelId, Long roomTypeId, LocalDate checkin, LocalDate checkout);
}
