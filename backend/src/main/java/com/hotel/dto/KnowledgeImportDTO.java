package com.hotel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量导入 FAQ 知识请求参数
 */
@Data
public class KnowledgeImportDTO {

    /** 所属酒店ID（NULL=平台公共知识） */
    private Long hotelId;

    /** 分类：酒店信息/服务设施/周边推荐/政策 */
    private String category;

    @NotEmpty(message = "至少导入一条知识")
    private List<QAPair> items;

    @Data
    public static class QAPair {

        @NotBlank(message = "问题不能为空")
        private String question;

        @NotBlank(message = "答案不能为空")
        private String answer;
    }
}
