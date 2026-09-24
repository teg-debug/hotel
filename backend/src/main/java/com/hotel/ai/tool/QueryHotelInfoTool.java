package com.hotel.ai.tool;

import com.hotel.entity.Hotel;
import com.hotel.mapper.HotelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 工具：查询酒店基本信息（名称/地址/星级/电话/入住退房时间/简介）
 */
@Component
@RequiredArgsConstructor
public class QueryHotelInfoTool {

    private final HotelMapper hotelMapper;

    @Tool(description = "查询酒店基本信息：名称、星级、地址、电话、入住/退房时间、简介。需提供酒店ID。")
    public String queryHotelInfo(QueryHotelInfoRequest request) {
        if (request.hotelId() == null || request.hotelId() <= 0) {
            return "请提供酒店ID";
        }
        Hotel hotel = hotelMapper.selectById(request.hotelId());
        if (hotel == null) {
            return "酒店不存在";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(hotel.getName()).append("】")
          .append(hotel.getCity()).append(" · ").append(hotel.getStarLevel()).append("星\n")
          .append("地址：").append(hotel.getAddress()).append("\n")
          .append("电话：").append(hotel.getPhone() == null ? "暂无" : hotel.getPhone()).append("\n")
          .append("入住/退房时间：").append(hotel.getCheckinTime()).append(" 至 ").append(hotel.getCheckoutTime()).append("\n");
        if (hotel.getDescription() != null && !hotel.getDescription().isBlank()) {
            sb.append("简介：").append(hotel.getDescription()).append("\n");
        }
        return sb.toString().trim();
    }

    public record QueryHotelInfoRequest(
            @ToolParam(description = "酒店ID", required = false) Long hotelId) {
    }
}
