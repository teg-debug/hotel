package com.hotel.service;

import com.hotel.common.PageResult;
import com.hotel.dto.KnowledgeImportDTO;
import com.hotel.dto.KnowledgeUpdateDTO;
import com.hotel.entity.KnowledgeBase;
import com.hotel.vo.KnowledgeVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库：FAQ 导入 / 文档上传切片向量化 / 编辑 / 删除 / 语义检索 / 管理分页
 */
public interface KnowledgeService {

    /** 批量导入 FAQ（自动向量化入库） */
    int importFaq(KnowledgeImportDTO dto);

    /** 上传文档（PDF/TXT/Markdown）：读取 → 切片 → 向量化 → 入库 */
    int uploadDocument(MultipartFile file, Long hotelId, String category);

    /** 编辑知识条目：内容变更后重建向量，停用时移出向量库 */
    KnowledgeVO update(Long id, KnowledgeUpdateDTO dto);

    /** 删除知识条目（同步删除向量） */
    void delete(Long id);

    /** 知识库语义检索（RAG 检索入口，含 Redis 缓存） */
    KnowledgeHit search(String query, Long hotelId);

    /** 知识库管理分页 */
    PageResult<KnowledgeVO> page(Long hotelId, int page, int size);

    /** 检索命中结果 */
    record KnowledgeHit(KnowledgeBase knowledge, double confidence) {
    }
}
