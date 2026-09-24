package com.hotel.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 批量生成房间请求参数
 * <p>房间号 = 楼层×100 + 编号，如 8 层 1-10 → 801~810</p>
 */
@Data
public class BatchCreateRoomDTO {

    @NotNull(message = "楼层不能为空")
    private Integer floor;

    @NotNull(message = "起始编号不能为空")
    private Integer startNo;

    @NotNull(message = "结束编号不能为空")
    private Integer endNo;
}
