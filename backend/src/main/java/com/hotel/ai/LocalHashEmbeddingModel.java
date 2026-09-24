package com.hotel.ai;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置本地哈希嵌入模型（演示用，零外部依赖）。
 *
 * <p>原理：把文本切成词元（中文按单字与相邻二元组，英文按词），
 * 用词元哈希映射到固定维度桶并按词频加权，再做 L2 归一化。
 * 它能反映字面重叠程度，但不具备语义泛化能力
 * （「退房时间」与「几点退房」不会被判为相近）。</p>
 *
 * <p>生产环境应开启 {@code app.ai.embedding.enabled=true} 使用语义嵌入服务
 * （如 BAAI/bge-m3），那里的向量才具备语义区分度。</p>
 */
public class LocalHashEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 384;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<float[]> vectors = embed(request.getInstructions());
        List<Embedding> embeddings = new ArrayList<>();
        for (float[] vector : vectors) {
            embeddings.add(new Embedding(vector, -1));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isBlank()) {
            return vector;
        }
        // 切词 + 词频（中文按单字与二元组，避免整句塌缩成单个词元）
        Map<String, Integer> tf = new HashMap<>();
        for (String token : TextTokenizer.tokenize(text)) {
            if (!token.isEmpty()) {
                tf.merge(token, 1, Integer::sum);
            }
        }
        // 词哈希落桶（词频加权）
        for (Map.Entry<String, Integer> entry : tf.entrySet()) {
            int bucket = Math.floorMod(entry.getKey().hashCode(), DIMENSIONS);
            vector[bucket] += entry.getValue();
        }
        // L2 归一化
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < DIMENSIONS; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return vector;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> result = new ArrayList<>();
        for (String text : texts) {
            result.add(embed(text));
        }
        return result;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }
}
