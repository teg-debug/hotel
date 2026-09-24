package com.hotel.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地知识检索打分器。
 *
 * <p>原先的关键词兜底用「命中的字符二元组数 ÷ 查询二元组总数」打分，存在两个问题：
 * 一是把「时」「间」这类高频字与「押金」这类低频词看得一样重，
 * 二是长文档天然吃亏，靠字符重叠很难区分强弱相关。</p>
 *
 * <p>这里改用 IDF 加权加词频饱和与文档长度归一（BM25 的思路），
 * 再按「查询词项里在语料中出现过的那些的 IDF 之和」归一化到 0~1，
 * 从而继续沿用知识条目上已配置的召回阈值。完全匹配的候选得分接近 1，
 * 只命中少量泛化词则明显偏低。</p>
 */
public final class KnowledgeRanker {

    /** BM25 词频饱和参数 */
    private static final double K1 = 1.2;
    /** BM25 文档长度归一参数 */
    private static final double B = 0.75;

    private KnowledgeRanker() {
    }

    /** 打分用词项：分词结果 + 命中的同义词组标记 */
    public static List<String> terms(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>(TextTokenizer.tokenize(text));
        terms.addAll(SynonymDictionary.markersOf(text));
        return new ArrayList<>(terms);
    }

    /**
     * 按候选语料建立打分器。
     *
     * @param query        用户问题
     * @param corpusTexts  候选文档文本，顺序即为后续 {@link Ranker#score(int)} 的下标
     */
    public static Ranker forCorpus(String query, List<String> corpusTexts) {
        List<String> texts = corpusTexts == null ? List.of() : corpusTexts;
        List<Map<String, Integer>> docTermFrequencies = new ArrayList<>(texts.size());
        Map<String, Integer> documentFrequency = new HashMap<>();
        long totalLength = 0;

        for (String text : texts) {
            Map<String, Integer> tf = new HashMap<>();
            for (String term : terms(text)) {
                tf.merge(term, 1, Integer::sum);
            }
            docTermFrequencies.add(tf);
            totalLength += tf.values().stream().mapToInt(Integer::intValue).sum();
            for (String term : tf.keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        double avgLength = texts.isEmpty() ? 1.0 : (double) totalLength / texts.size();
        return new Ranker(terms(query), docTermFrequencies, documentFrequency, texts.size(), avgLength);
    }

    /** 针对一批候选文档的打分器 */
    public static final class Ranker {

        private final List<String> queryTerms;
        private final List<Map<String, Integer>> docTermFrequencies;
        private final Map<String, Integer> documentFrequency;
        private final int corpusSize;
        private final double avgLength;
        private final double maxScore;
        private final Map<String, Double> idfCache = new HashMap<>();

        private Ranker(List<String> queryTerms, List<Map<String, Integer>> docTermFrequencies,
                       Map<String, Integer> documentFrequency, int corpusSize, double avgLength) {
            this.queryTerms = queryTerms;
            this.docTermFrequencies = docTermFrequencies;
            this.documentFrequency = documentFrequency;
            this.corpusSize = corpusSize;
            this.avgLength = avgLength <= 0 ? 1.0 : avgLength;
            this.maxScore = computeMaxScore();
        }

        /**
         * 归一化上界：查询词项中在语料里出现过的那些的 IDF 之和。
         *
         * <p>只统计出现过的词项：全部候选都不含某个词时，它对本次区分毫无贡献，
         * 计入分母只会把真实相关的候选一起压低。</p>
         */
        private double computeMaxScore() {
            double sum = 0;
            for (String term : queryTerms) {
                if (documentFrequency.getOrDefault(term, 0) > 0) {
                    sum += idf(term);
                }
            }
            return sum;
        }

        private double idf(String term) {
            return idfCache.computeIfAbsent(term, key -> {
                int frequency = documentFrequency.getOrDefault(key, 0);
                return Math.log(1.0 + (corpusSize - frequency + 0.5) / (frequency + 0.5));
            });
        }

        /** 第 {@code docIndex} 篇候选文档的归一化相关度，取值 0~1 */
        public double score(int docIndex) {
            if (maxScore <= 0 || docIndex < 0 || docIndex >= docTermFrequencies.size()) {
                return 0;
            }
            Map<String, Integer> tf = docTermFrequencies.get(docIndex);
            int length = tf.values().stream().mapToInt(Integer::intValue).sum();
            if (length == 0) {
                return 0;
            }
            double score = 0;
            for (String term : queryTerms) {
                Integer frequency = tf.get(term);
                if (frequency == null || frequency == 0) {
                    continue;
                }
                double denominator = frequency + K1 * (1 - B + B * length / avgLength);
                score += idf(term) * (frequency * (K1 + 1)) / denominator;
            }
            return maxScore <= 0 ? 0 : Math.min(1.0, score / maxScore);
        }
    }
}
