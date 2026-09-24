package com.hotel.ai.tool;

import com.hotel.common.PageResult;
import com.hotel.dto.HotelSearchDTO;
import com.hotel.service.HotelSearchService;
import com.hotel.vo.HotelSearchVO;
import com.hotel.vo.RoomTypeSearchVO;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 工具：查询指定日期/酒店/房型的可用房间数
 * 复用已有 HotelSearchService（Redis 5 分钟缓存 + 日期冲突检查）
 */
@Component
@RequiredArgsConstructor
public class QueryRoomTool {

    private final HotelSearchService searchService;

    @Tool(description = "查询酒店的可用房型与可用房间数。需提供入住日期与离店日期(yyyy-MM-dd)，可指定酒店ID或城市/酒店名关键词。")
    public String queryRoom(QueryRoomRequest request) {
        LocalDate checkin = parse(request.checkinDate());
        LocalDate checkout = parse(request.checkoutDate());
        if (checkin == null || checkout == null) {
            return "日期格式不正确，请提供 yyyy-MM-dd 格式的入住和离店日期";
        }
        if (!checkout.isAfter(checkin)) {
            return "离店日期必须晚于入住日期";
        }

        HotelSearchDTO dto = new HotelSearchDTO();
        dto.setCheckin(checkin);
        dto.setCheckout(checkout);
        dto.setCity(hasText(request.city()) ? request.city() : null);
        dto.setKeyword(hasText(request.keyword()) ? request.keyword() : null);
        dto.setPage(1);
        dto.setSize(50);

        PageResult<HotelSearchVO> page = searchService.search(dto);
        List<HotelSearchVO> hotels = page.getRecords().stream()
                .filter(h -> !isProvided(request.hotelId()) || h.getHotelId().equals(request.hotelId()))
                .toList();
        if (hotels.isEmpty()) {
            return "未找到符合日期条件的酒店";
        }

        StringBuilder sb = new StringBuilder();
        for (HotelSearchVO hotel : hotels) {
            sb.append("【").append(hotel.getHotelName()).append("】")
              .append(hotel.getCity()).append(" · ").append(hotel.getStarLevel()).append("星\n");
            boolean found = false;
            for (RoomTypeSearchVO rt : hotel.getAvailableRoomTypes()) {
                if (isProvided(request.roomTypeId()) && !rt.getId().equals(request.roomTypeId())) {
                    continue;
                }
                found = true;
                sb.append("  - ").append(rt.getName())
                  .append("：价格 ").append(rt.getPrice()).append(" 元/晚")
                  .append("，可用 ").append(rt.getAvailableCount()).append(" 间")
                  .append(rt.getBreakfast() != null && rt.getBreakfast() == 1 ? "（含早）" : "").append("\n");
            }
            if (!found) {
                sb.append("  - 该房型在所选日期无可用房间\n");
            }
        }
        return sb.toString();
    }

    private LocalDate parse(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 数字字段未提供（null 或 <=0）视为“未指定”，避免 LLM 默认填 0 导致误过滤 */
    private boolean isProvided(Long v) {
        return v != null && v > 0;
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 入参结构：由 LLM 从用户对话中抽取；可选字段标记 required=false，
     * 避免模型补齐 0/空串默认值（仅入住/离店日期为必填）
     */
    public record QueryRoomRequest(
            @ToolParam(description = "入住日期，格式 yyyy-MM-dd，如 2026-09-01") String checkinDate,
            @ToolParam(description = "离店日期，格式 yyyy-MM-dd") String checkoutDate,
            @ToolParam(description = "城市，如上海，可选", required = false) String city,
            @ToolParam(description = "酒店ID，可选", required = false) Long hotelId,
            @ToolParam(description = "房型ID，可选", required = false) Long roomTypeId,
            @ToolParam(description = "酒店名称关键词，可选", required = false) String keyword) {
    }
}
