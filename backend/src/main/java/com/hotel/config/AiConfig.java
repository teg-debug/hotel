package com.hotel.config;

import com.hotel.ai.LocalHashEmbeddingModel;
import com.hotel.ai.RecordingToolCallback;
import com.hotel.ai.tool.CreateOrderTool;
import com.hotel.ai.tool.CreateTicketTool;
import com.hotel.ai.tool.QueryHotelInfoTool;
import com.hotel.ai.tool.QueryOrderTool;
import com.hotel.ai.tool.QueryRoomTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;

/**
 * Spring AI 集成配置：
 * <ul>
 *   <li>DeepSeek ChatClient（OpenAI 兼容协议，用于对话/工具调用）</li>
 *   <li>ChatMemory：多轮对话上下文（仓库外置到 Redis，会话ID维度，多实例共享）</li>
 *   <li>向量库：默认 SimpleVectorStore（内存，零依赖）；开启 app.ai.chroma.enabled 后使用本地 Chroma</li>
 *   <li>嵌入模型：默认 LocalHashEmbeddingModel（演示）；开启 app.ai.embedding.enabled 后使用 OpenAI 兼容语义嵌入</li>
 *   <li>Function Calling：注册 5 个业务工具（查房态/下单/查订单/建工单/查酒店）</li>
 * </ul>
 */
@Configuration
public class AiConfig {

    // ==================== 对话模型（DeepSeek） ====================

    /**
     * 多轮对话记忆：仓库外置到 Redis，上下文窗口按条数截断。
     *
     * <p>仓库必须指向 Redis 实现。框架默认的 {@code InMemoryChatMemoryRepository}
     * 把上下文放在当前 JVM 堆里，多实例部署时同一个会话的第 N 轮可能落到另一个实例上：
     * 那个实例读不到前文，模型会「失忆」；两个实例各自累积的上下文还会互相矛盾。
     * 换成 Redis 后，任意实例发起的一轮对话都写回同一份上下文。</p>
     *
     * <p>注意 Spring AI 1.0.0 的 {@code MessageWindowChatMemory} 按「条数」截断，
     * 不保证工具调用与其结果成对；窗口切分产生的残缺序列由
     * {@code ChatMemoryMessageCodec} 在读写两侧修复。</p>
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository,
                                 @Value("${app.chat.memory-window-size:20}") int memoryWindowSize) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(Math.max(2, memoryWindowSize))
                .build();
    }

    /**
     * 统一的 ChatClient：默认带上多轮记忆 Adapter + 业务工具（Function Calling）。
     *
     * <p>工具先经 {@link MethodToolCallbackProvider} 转成 ToolCallback 再逐个包装，
     * 以便记录本次实际触发了哪些工具——最终 ChatResponse 里不含工具调用信息。</p>
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel, ChatMemory chatMemory,
                                 QueryRoomTool queryRoomTool, CreateOrderTool createOrderTool,
                                 QueryOrderTool queryOrderTool, CreateTicketTool createTicketTool,
                                 QueryHotelInfoTool queryHotelInfoTool) {
        List<ToolCallback> toolCallbacks = Arrays.stream(MethodToolCallbackProvider.builder()
                        .toolObjects(queryRoomTool, createOrderTool, queryOrderTool,
                                createTicketTool, queryHotelInfoTool)
                        .build()
                        .getToolCallbacks())
                .<ToolCallback>map(RecordingToolCallback::new)
                .toList();

        return ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultToolCallbacks(toolCallbacks)
                .build();
    }

    // ==================== 嵌入模型 ====================

    /** 可选：OpenAI 兼容语义嵌入（如 SiliconFlow 的 BAAI/bge-m3），生产环境推荐开启 */
    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "app.ai.embedding", name = "enabled", havingValue = "true")
    public EmbeddingModel openAiEmbeddingModel(@Value("${app.ai.embedding.base-url}") String baseUrl,
                                               @Value("${app.ai.embedding.api-key}") String apiKey) {
        return new OpenAiEmbeddingModel(OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build());
    }

    /** 默认：本地哈希嵌入（零外部依赖，演示可用） */
    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel localHashEmbeddingModel() {
        return new LocalHashEmbeddingModel();
    }

    // ==================== 向量库 ====================

    /** 可选：本地 Chroma（需先启动：chroma run --path ./chroma-data） */
    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "app.ai.chroma", name = "enabled", havingValue = "true")
    public VectorStore chromaVectorStore(EmbeddingModel embeddingModel,
                                         @Value("${app.ai.chroma.url}") String url,
                                         @Value("${app.ai.chroma.collection}") String collection) {
        ChromaApi chromaApi = new ChromaApi(url, RestClient.builder(), new ObjectMapper());
        return ChromaVectorStore.builder(chromaApi, embeddingModel)
                .collectionName(collection)
                .build();
    }

    /** 默认：内存向量库（零依赖，重启即清空，需重新导入知识库） */
    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
