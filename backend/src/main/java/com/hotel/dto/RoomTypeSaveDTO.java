package com.hotel.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 新增/修改房型请求参数（经营者/管理员）
 */
@Data
public class RoomTypeSaveDTO {

    /** 所属酒店ID（仅新增时使用，修改时忽略） */
    @NotNull(message = "酒店ID不能为空")
    private Long hotelId;

    @NotBlank(message = "房型名称不能为空")
    private String name;

    /** 床型：大床/双床 */
    private String bedType;

    /** 面积（平方米） */
    private Integer area;

    @NotNull(message = "最大入住人数不能为空")
    private Integer maxGuests;

    /** 门市价（元/晚） */
    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格必须大于0")
    private BigDecimal price;

    /** 是否含早餐：0-否 1-是 */
    private Integer breakfast;

    private String imgUrl;

    /** 状态：0-停售 1-在售 */
    private Integer status;
}
