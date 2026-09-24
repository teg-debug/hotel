package com.hotel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 客服知识库条目
 */
@Data
@TableName("knowledge_base")
public class KnowledgeBase {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属酒店ID（NULL=平台公共知识） */
    private Long hotelId;

    /** 标准问题 */
    private String question;

    /** 标准答案 */
    private String answer;

    /** 分类：酒店信息/服务设施/周边推荐/政策 */
    private String category;

    /** 召回阈值（低于该值不采纳，防幻觉） */
    private BigDecimal similarityThreshold;

    /** 向量库中的文档ID */
    private String vectorId;

    /** 状态：0-停用 1-启用 */
    private Integer status;

    /** 命中次数（热问统计） */
    private Integer hitCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
