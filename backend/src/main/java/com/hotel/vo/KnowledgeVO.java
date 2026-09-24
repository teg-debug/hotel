package com.hotel.vo;

import com.hotel.entity.KnowledgeBase;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 知识库条目 VO
 */
@Data
public class KnowledgeVO {

    private Long id;
    private Long hotelId;
    private String question;
    private String answer;
    private String category;
    private BigDecimal similarityThreshold;
    private Integer status;
    private Integer hitCount;

    public static KnowledgeVO from(KnowledgeBase kb) {
        KnowledgeVO vo = new KnowledgeVO();
        vo.setId(kb.getId());
        vo.setHotelId(kb.getHotelId());
        vo.setQuestion(kb.getQuestion());
        vo.setAnswer(kb.getAnswer());
        vo.setCategory(kb.getCategory());
        vo.setSimilarityThreshold(kb.getSimilarityThreshold());
        vo.setStatus(kb.getStatus());
        vo.setHitCount(kb.getHitCount());
        return vo;
    }
}
