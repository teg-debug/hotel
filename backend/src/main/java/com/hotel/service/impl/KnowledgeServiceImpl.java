package com.hotel.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.ai.KnowledgeRanker;
import com.hotel.ai.SynonymDictionary;
import com.hotel.common.BusinessException;
import com.hotel.common.PageResult;
import com.hotel.common.RedisCacheHelper;
import com.hotel.dto.KnowledgeImportDTO;
import com.hotel.dto.KnowledgeUpdateDTO;
import com.hotel.entity.KnowledgeBase;
import com.hotel.mapper.KnowledgeBaseMapper;
import com.hotel.security.UserContext;
import com.hotel.service.HotelScopeService;
import com.hotel.service.KnowledgeService;
import com.hotel.vo.KnowledgeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 知识库实现：
 * <ul>
 *   <li>FAQ 批量导入 / 文档上传（PDF/TXT/MD）→ 切片 → 向量化 → Chroma/内存向量库</li>
 *   <li>检索：向量 Top-K + 距离阈值过滤 → MySQL 关键词兜底 → Redis 缓存命中结果</li>
 *   <li>删除：同步删除向量，保证知识一致性</li>
 *   <li>权限：按酒店维度隔离，平台级知识（无酒店归属）仅系统管理员可维护</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl implements KnowledgeService {

    /** 知识检索缓存前缀 */
    private static final String CACHE_PREFIX = "chat:kb:q:";
    private static final String CACHE_NONE = "NONE";
    private static final double DEFAULT_THRESHOLD = 0.700;
    /** 关键词兜底：候选数量上限 */
    private static final int MAX_CANDIDATES = 50;
    /** 关键词兜底：召回词项数量上限，避免 LIKE 条件过多拖慢查询 */
    private static final int MAX_RECALL_TERMS = 40;

    private final KnowledgeBaseMapper kbMapper;
    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final HotelScopeService hotelScopeService;
    private final RedisCacheHelper redisCacheHelper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int importFaq(KnowledgeImportDTO dto) {
        checkKnowledgeWriteAccess(dto.getHotelId());
        int count = 0;
        for (KnowledgeImportDTO.QAPair pair : dto.getItems()) {
            KnowledgeBase kb = new KnowledgeBase();
            kb.setHotelId(dto.getHotelId());
            kb.setCategory(dto.getCategory());
            kb.setQuestion(pair.getQuestion());
            kb.setAnswer(pair.getAnswer());
            kb.setSimilarityThreshold(BigDecimal.valueOf(DEFAULT_THRESHOLD));
            kb.setStatus(1);
            kb.setHitCount(0);
            kbMapper.insert(kb);
            addToVector(kb, pair.getQuestion() + "\n" + pair.getAnswer());
            count++;
        }
        invalidateCache();
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int uploadDocument(MultipartFile file, Long hotelId, String category) {
        checkKnowledgeWriteAccess(hotelId);
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "";
        List<Document> documents;
        try {
            ByteArrayResource resource = new ByteArrayResource(file.getBytes());
            documents = switch (ext) {
                case "pdf" -> new PagePdfDocumentReader(resource).read();
                case "md", "markdown" -> new MarkdownDocumentReader(new String(file.getBytes(), StandardCharsets.UTF_8)).read();
                case "txt" -> List.of(new Document(new String(file.getBytes(), StandardCharsets.UTF_8)));
                default -> throw new BusinessException("仅支持 PDF/TXT/Markdown 格式");
            };
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("文档解析失败: " + e.getMessage());
        }
        // 切片（DocumentTransformer）
        List<Document> chunks = new TokenTextSplitter().apply(documents);

        int count = 0;
        for (Document chunk : chunks) {
            KnowledgeBase kb = new KnowledgeBase();
            kb.setHotelId(hotelId);
            kb.setCategory(category);
            String text = chunk.getText();
            kb.setQuestion(truncate(text, 100));
            kb.setAnswer(text);
            kb.setSimilarityThreshold(BigDecimal.valueOf(DEFAULT_THRESHOLD));
            kb.setStatus(1);
            kb.setHitCount(0);
            kbMapper.insert(kb);
            addToVector(kb, text);
            count++;
        }
        invalidateCache();
        return count;
    }

    /**
     * 编辑知识条目。
     *
     * <p>内容变更后必须重建向量：向量库里的文档是导入时的文本快照，
     * 只改数据库会让检索继续命中旧内容，出现「后台改了但客服还按旧答案回答」。</p>
     *
     * <p>停用条目会从向量库移除；启用条目重新入库。归属酒店不可变更，
     * 因为它同时决定了知识的生效范围与维护权限。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeVO update(Long id, KnowledgeUpdateDTO dto) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) {
            throw new BusinessException("知识条目不存在");
        }
        checkKnowledgeWriteAccess(kb.getHotelId());

        if (dto.getQuestion() != null && !dto.getQuestion().isBlank()) {
            kb.setQuestion(dto.getQuestion().trim());
        }
        if (dto.getAnswer() != null && !dto.getAnswer().isBlank()) {
            kb.setAnswer(dto.getAnswer().trim());
        }
        if (dto.getCategory() != null) {
            kb.setCategory(dto.getCategory().isBlank() ? null : dto.getCategory().trim());
        }
        if (dto.getSimilarityThreshold() != null) {
            double threshold = dto.getSimilarityThreshold().doubleValue();
            if (threshold < 0.1 || threshold > 1.0) {
                throw new BusinessException("召回阈值需在 0.1 到 1.0 之间");
            }
            kb.setSimilarityThreshold(dto.getSimilarityThreshold());
        }
        if (dto.getStatus() != null) {
            if (dto.getStatus() != 0 && dto.getStatus() != 1) {
                throw new BusinessException("状态仅支持 0-停用 1-启用");
            }
            kb.setStatus(dto.getStatus());
        }
        kbMapper.updateById(kb);

        if (kb.getStatus() != null && kb.getStatus() == 1) {
            addToVector(kb, kb.getQuestion() + "\n" + kb.getAnswer());
        } else {
            removeFromVector(kb);
        }
        invalidateCache();
        log.info("知识条目已更新 id={} hotelId={} status={} 阈值={}",
                id, kb.getHotelId(), kb.getStatus(), kb.getSimilarityThreshold());
        return KnowledgeVO.from(kb);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) {
            return;
        }
        checkKnowledgeWriteAccess(kb.getHotelId());
        if (kb.getVectorId() != null) {
            try {
                vectorStore.delete(List.of(kb.getVectorId()));
            } catch (Exception e) {
                log.warn("删除知识时移除向量失败 knowledgeId={} vectorId={}", id, kb.getVectorId(), e);
            }
        }
        kbMapper.deleteById(id);
        invalidateCache();
        log.info("知识条目已删除 id={} hotelId={}", id, kb.getHotelId());
    }

    @Override
    public KnowledgeHit search(String query, Long hotelId) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String cacheKey = CACHE_PREFIX + md5((hotelId == null ? "0" : hotelId) + ":" + query.trim());

        // 1. 读缓存
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            if (CACHE_NONE.equals(cached)) {
                return null;
            }
            try {
                JsonNode node = objectMapper.readTree(cached);
                KnowledgeBase kb = kbMapper.selectById(node.get("kbId").asLong());
                if (kb != null) {
                    return new KnowledgeHit(kb, node.get("confidence").asDouble());
                }
            } catch (Exception ignored) {
                // 缓存损坏则忽略，重新检索
            }
        }

        // 2. 向量检索 Top-K
        //    查询侧与索引侧使用同一套同义词扩充，否则两侧向量不在同一表示上、不可比
        String expandedQuery = SynonymDictionary.expandForEmbedding(query);
        KnowledgeHit best = null;
        // 查询向量按需计算：向量库返回 distance 时无需再自行向量化
        float[] queryVector = null;
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(expandedQuery).topK(5).build());
        for (Document doc : docs) {
            Object kbIdObj = doc.getMetadata().get("knowledgeId");
            if (kbIdObj == null) {
                continue;
            }
            KnowledgeBase kb = kbMapper.selectById(Long.valueOf(kbIdObj.toString()));
            if (kb == null || kb.getStatus() == null || kb.getStatus() != 1 || !matchHotel(kb, hotelId)) {
                continue;
            }
            // 置信度：优先取向量库返回的 distance；SimpleVectorStore 不返回时自行计算余弦相似度
            double confidence;
            Object distanceObj = doc.getMetadata().get("distance");
            if (distanceObj != null) {
                confidence = Math.max(0.0, 1.0 - Double.parseDouble(distanceObj.toString()));
            } else {
                if (queryVector == null) {
                    queryVector = embeddingModel.embed(expandedQuery);
                }
                confidence = cosine(queryVector, embeddingModel.embed(doc.getText()));
            }
            double threshold = kb.getSimilarityThreshold() == null
                    ? DEFAULT_THRESHOLD : kb.getSimilarityThreshold().doubleValue();
            if (confidence >= threshold && (best == null || confidence > best.confidence())) {
                best = new KnowledgeHit(kb, confidence);
            }
        }

        // 3. 关键词兜底（内存向量库重启后为空时仍可命中）
        if (best == null) {
            best = keywordFallback(query, hotelId);
        }

        // 4. 写缓存 + 热问计数
        if (best != null) {
            kbMapper.update(null, new UpdateWrapper<KnowledgeBase>()
                    .eq("id", best.knowledge().getId())
                    .setSql("hit_count = hit_count + 1"));
            try {
                stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(
                        Map.of("kbId", best.knowledge().getId(), "confidence", best.confidence())), 1, TimeUnit.HOURS);
            } catch (Exception ignored) {
            }
        } else {
            stringRedisTemplate.opsForValue().set(cacheKey, CACHE_NONE, 60, TimeUnit.SECONDS);
        }
        return best;
    }

    /**
     * 知识分页：非系统管理员只能看到自己酒店的条目与平台级条目，
     * 平台级条目的维护权限另行校验。
     */
    @Override
    public PageResult<KnowledgeVO> page(Long hotelId, int page, int size) {
        Integer role = UserContext.getRole();
        if (role == null || role < 1) {
            throw new BusinessException("无权限查看知识库");
        }
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        if (hotelId != null) {
            hotelScopeService.checkHotelAccess(hotelId, role);
            wrapper.eq(KnowledgeBase::getHotelId, hotelId);
        } else {
            List<Long> hotelIds = hotelScopeService.allowedHotelIds(role);
            if (hotelIds != null) {
                if (hotelIds.isEmpty()) {
                    wrapper.isNull(KnowledgeBase::getHotelId);
                } else {
                    wrapper.and(w -> w.isNull(KnowledgeBase::getHotelId)
                            .or().in(KnowledgeBase::getHotelId, hotelIds));
                }
            }
        }
        wrapper.orderByDesc(KnowledgeBase::getId);
        Page<KnowledgeBase> p = kbMapper.selectPage(new Page<>(page, size), wrapper);
        List<KnowledgeVO> records = p.getRecords().stream().map(KnowledgeVO::from).toList();
        return PageResult.of(p.getTotal(), p.getPages(), p.getCurrent(), p.getSize(), records);
    }

    // ==================== 私有工具 ====================

    /**
     * 知识写入权限：酒店级知识要求对目标酒店有权限；
     * 平台级知识（无酒店归属）对所有酒店生效，仅系统管理员可维护。
     */
    private void checkKnowledgeWriteAccess(Long hotelId) {
        Integer role = UserContext.getRole();
        if (role == null || role < 1) {
            throw new BusinessException("无权限操作知识库");
        }
        if (hotelId == null) {
            if (!hotelScopeService.isSysAdmin(role)) {
                throw new BusinessException("平台级知识仅系统管理员可维护");
            }
            return;
        }
        hotelScopeService.checkHotelAccess(hotelId, role);
    }

    /** 向量化入库：知识ID 即文档ID，便于删除时同步移除向量 */
    private void addToVector(KnowledgeBase kb, String text) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("knowledgeId", String.valueOf(kb.getId()));
        if (kb.getHotelId() != null) {
            metadata.put("hotelId", kb.getHotelId());
        }
        // 入库文本追加同义词组词，使同义表达在向量空间里也彼此靠近
        Document doc = new Document(String.valueOf(kb.getId()),
                SynonymDictionary.expandForEmbedding(text), metadata);
        vectorStore.add(List.of(doc));
        kb.setVectorId(doc.getId());
        kbMapper.updateById(kb);
    }

    /** 从向量库移除：向量库不可用时不阻断主流程，仅记录告警 */
    private void removeFromVector(KnowledgeBase kb) {
        if (kb.getVectorId() == null) {
            return;
        }
        try {
            vectorStore.delete(List.of(kb.getVectorId()));
        } catch (Exception e) {
            log.warn("移除向量失败 knowledgeId={} vectorId={}", kb.getId(), kb.getVectorId(), e);
        }
        kb.setVectorId(null);
        kbMapper.updateById(kb);
    }

    /** 知识归属过滤：全局知识(NULL)对所有酒店生效，酒店知识仅对指定酒店生效 */
    private boolean matchHotel(KnowledgeBase kb, Long hotelId) {
        if (kb.getHotelId() == null) {
            return true;
        }
        return hotelId != null && kb.getHotelId().equals(hotelId);
    }

    /**
     * 关键词兜底检索。
     *
     * <p>召回用字符二元组加同义词扩展：扩展这一步让「有停车位吗」这类问题能召回
     * 「酒店提供停车场」的条目，否则连候选都进不了。<br>
     * 打分交给 {@link KnowledgeRanker}，按 IDF 加权后归一化到 0~1，
     * 因此继续沿用每条知识自己的召回阈值，不需要为兜底另设一套标准。</p>
     */
    private KnowledgeHit keywordFallback(String query, Long hotelId) {
        List<String> recallTerms = recallTerms(query);
        if (recallTerms.isEmpty()) {
            return null;
        }
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBase::getStatus, 1)
                .and(w -> {
                    for (int i = 0; i < recallTerms.size(); i++) {
                        if (i > 0) {
                            w.or();
                        }
                        w.like(KnowledgeBase::getQuestion, escapeLike(recallTerms.get(i)))
                                .or().like(KnowledgeBase::getAnswer, escapeLike(recallTerms.get(i)));
                    }
                });
        if (hotelId != null) {
            wrapper.and(w -> w.isNull(KnowledgeBase::getHotelId).or().eq(KnowledgeBase::getHotelId, hotelId));
        }
        List<KnowledgeBase> candidates = kbMapper.selectList(wrapper.last("LIMIT " + MAX_CANDIDATES));
        if (candidates.isEmpty()) {
            return null;
        }

        List<String> corpus = candidates.stream()
                .map(kb -> kb.getQuestion() + "\n" + kb.getAnswer())
                .toList();
        KnowledgeRanker.Ranker ranker = KnowledgeRanker.forCorpus(query, corpus);

        KnowledgeBase bestKb = null;
        double bestScore = 0;
        for (int i = 0; i < candidates.size(); i++) {
            KnowledgeBase kb = candidates.get(i);
            double threshold = kb.getSimilarityThreshold() == null
                    ? DEFAULT_THRESHOLD : kb.getSimilarityThreshold().doubleValue();
            double score = ranker.score(i);
            if (score >= threshold && score > bestScore) {
                bestScore = score;
                bestKb = kb;
            }
        }
        if (bestKb == null) {
            log.debug("关键词兜底未达阈值 候选数={} 查询长度={}", candidates.size(), query.length());
            return null;
        }
        log.info("关键词兜底命中 knowledgeId={} 分数={} 候选数={}",
                bestKb.getId(), String.format("%.3f", bestScore), candidates.size());
        return new KnowledgeHit(bestKb, bestScore);
    }

    /** 召回词项：字符二元组 + 查询命中的同义词组全部词，去重保序并限制数量 */
    private List<String> recallTerms(String query) {
        Set<String> terms = new LinkedHashSet<>(bigrams(query));
        terms.addAll(SynonymDictionary.expandTerms(query));
        return terms.stream()
                .filter(term -> term != null && !term.isBlank())
                .limit(MAX_RECALL_TERMS)
                .toList();
    }

    /** 知识变更后清理检索缓存：使用游标扫描，避免 KEYS 阻塞 Redis */
    private void invalidateCache() {
        try {
            redisCacheHelper.deleteByPattern(CACHE_PREFIX + "*");
        } catch (Exception ignored) {
        }
    }

    /** 余弦相似度（用于向量库未返回 distance 时的置信度计算） */
    private double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double norm = Math.sqrt(normA) * Math.sqrt(normB);
        if (norm == 0) {
            return 0;
        }
        return Math.max(0.0, Math.min(1.0, dot / norm));
    }

    /** 字符二元组（bigram）：中文无空格分词，用相邻两字窗口做模糊匹配，去重保序 */
    private List<String> bigrams(String text) {
        if (text == null) {
            return List.of();
        }
        String cleaned = text.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", "");
        if (cleaned.length() < 2) {
            return List.of();
        }
        Set<String> set = new LinkedHashSet<>();
        for (int i = 0; i < cleaned.length() - 1; i++) {
            set.add(cleaned.substring(i, i + 2));
        }
        return new ArrayList<>(set);
    }

    /** 转义 LIKE 通配符，避免查询串中的 % 或 _ 触发全表匹配 */
    private String escapeLike(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String truncate(String text, int max) {
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max);
    }

    private String md5(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
