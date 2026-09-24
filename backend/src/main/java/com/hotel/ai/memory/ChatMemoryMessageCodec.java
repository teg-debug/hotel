package com.hotel.ai.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话记忆的消息编解码器。
 *
 * <p>Spring AI 的 {@link Message} 是多态接口，若直接交给 Redis 的 JSON 序列化器，
 * 就必须依赖 {@code @class} 字段做多态还原——既扩大了反序列化攻击面，
 * 也把存储格式绑死在框架实现上。这里显式定义一层稳定的中间格式，
 * 只记录「消息类型 + 文本 + 工具调用」，框架升级不会影响 Redis 里已有的数据。</p>
 *
 * <p>编码与解码两侧都会做一次「工具调用配对修复」。多轮窗口是按条数截断的，
 * 可能正好把 {@code assistant(tool_calls)} 与其后的 {@code tool(结果)} 从中间切开；
 * 而模型接口要求两者必须成对出现，残缺序列会被直接判为非法请求。
 * 因此这里成对保留、成对丢弃，宁可少一轮工具结果，也不把非法序列送给模型。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMemoryMessageCodec {

    private static final String FIELD_TYPE = "type";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_TOOL_CALLS = "toolCalls";
    private static final String FIELD_TOOL_RESPONSES = "toolResponses";
    private static final String FIELD_ID = "id";
    private static final String FIELD_TOOL_TYPE = "toolType";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_ARGUMENTS = "arguments";
    private static final String FIELD_DATA = "data";

    private final ObjectMapper objectMapper;

    /** 把一轮对话窗口编码成 JSON 数组字符串；返回值一定是合法序列（已修复残缺工具调用对） */
    public String encode(List<Message> messages) {
        List<Message> repaired = repairBrokenToolPairs(messages);
        ArrayNode array = objectMapper.createArrayNode();
        for (Message message : repaired) {
            ObjectNode node = array.addObject();
            node.put(FIELD_TYPE, message.getMessageType().name());
            node.put(FIELD_TEXT, message.getText() == null ? "" : message.getText());
            if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                ArrayNode calls = node.putArray(FIELD_TOOL_CALLS);
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    ObjectNode callNode = calls.addObject();
                    callNode.put(FIELD_ID, call.id());
                    callNode.put(FIELD_TOOL_TYPE, call.type());
                    callNode.put(FIELD_NAME, call.name());
                    callNode.put(FIELD_ARGUMENTS, call.arguments());
                }
            }
            if (message instanceof ToolResponseMessage toolResponse) {
                ArrayNode responses = node.putArray(FIELD_TOOL_RESPONSES);
                for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                    ObjectNode responseNode = responses.addObject();
                    responseNode.put(FIELD_ID, response.id());
                    responseNode.put(FIELD_NAME, response.name());
                    responseNode.put(FIELD_DATA, response.responseData());
                }
            }
        }
        try {
            return objectMapper.writeValueAsString(array);
        } catch (Exception e) {
            throw new IllegalStateException("会话记忆编码失败", e);
        }
    }

    /**
     * 解码 JSON 数组字符串。
     *
     * <p>单条消息解析失败只跳过该条并记日志，不让整段记忆读取失败——
     * 否则一条脏数据就会让该会话永久失去多轮上下文。</p>
     */
    public List<Message> decode(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("会话记忆解码失败，按无历史上下文处理", e);
            return List.of();
        }
        if (!root.isArray()) {
            log.warn("会话记忆格式异常，期望 JSON 数组但得到 {}", root.getNodeType());
            return List.of();
        }
        List<Message> messages = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            Message message = toMessage(node);
            if (message != null) {
                messages.add(message);
            }
        }
        return repairBrokenToolPairs(messages);
    }

    /** 单条消息还原；类型未知或结构不合法时返回 null */
    private Message toMessage(JsonNode node) {
        String rawType = node.path(FIELD_TYPE).asText("");
        String text = node.path(FIELD_TEXT).asText("");
        MessageType type;
        try {
            type = MessageType.valueOf(rawType);
        } catch (IllegalArgumentException e) {
            log.warn("会话记忆中出现未知消息类型，已跳过 type={}", rawType);
            return null;
        }
        return switch (type) {
            case USER -> new UserMessage(text);
            case SYSTEM -> new SystemMessage(text);
            case ASSISTANT -> new AssistantMessage(text, Map.of(), readToolCalls(node));
            case TOOL -> new ToolResponseMessage(readToolResponses(node));
        };
    }

    private List<AssistantMessage.ToolCall> readToolCalls(JsonNode node) {
        JsonNode calls = node.path(FIELD_TOOL_CALLS);
        if (!calls.isArray() || calls.isEmpty()) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> result = new ArrayList<>(calls.size());
        for (JsonNode call : calls) {
            result.add(new AssistantMessage.ToolCall(
                    call.path(FIELD_ID).asText(""),
                    call.path(FIELD_TOOL_TYPE).asText("function"),
                    call.path(FIELD_NAME).asText(""),
                    call.path(FIELD_ARGUMENTS).asText("{}")));
        }
        return result;
    }

    private List<ToolResponseMessage.ToolResponse> readToolResponses(JsonNode node) {
        JsonNode responses = node.path(FIELD_TOOL_RESPONSES);
        if (!responses.isArray() || responses.isEmpty()) {
            return List.of();
        }
        List<ToolResponseMessage.ToolResponse> result = new ArrayList<>(responses.size());
        for (JsonNode response : responses) {
            result.add(new ToolResponseMessage.ToolResponse(
                    response.path(FIELD_ID).asText(""),
                    response.path(FIELD_NAME).asText(""),
                    response.path(FIELD_DATA).asText("")));
        }
        return result;
    }

    /**
     * 修复被窗口截断切开的工具调用对。
     *
     * <p>先统计哪些 {@code tool_call_id} 两侧齐全，再按配对情况重建：
     * 助手消息只保留有结果的工具调用（全部失配时退化为纯文本消息，无文本则整条丢弃），
     * 工具结果消息只保留能对上助手调用的条目。</p>
     */
    private List<Message> repairBrokenToolPairs(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        Map<String, Boolean> callIdPaired = new HashMap<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    callIdPaired.putIfAbsent(call.id(), Boolean.FALSE);
                }
            } else if (message instanceof ToolResponseMessage toolResponse) {
                for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                    if (callIdPaired.containsKey(response.id())) {
                        callIdPaired.put(response.id(), Boolean.TRUE);
                    }
                }
            }
        }

        Set<String> matchedIds = new LinkedHashSet<>();
        callIdPaired.forEach((id, paired) -> {
            if (Boolean.TRUE.equals(paired)) {
                matchedIds.add(id);
            }
        });

        List<Message> repaired = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                List<AssistantMessage.ToolCall> keptCalls = assistant.getToolCalls().stream()
                        .filter(call -> matchedIds.contains(call.id()))
                        .toList();
                if (keptCalls.size() == assistant.getToolCalls().size()) {
                    repaired.add(message);
                } else if (!keptCalls.isEmpty()) {
                    repaired.add(new AssistantMessage(assistant.getText(), Map.of(), keptCalls));
                } else if (StringUtils.hasText(assistant.getText())) {
                    repaired.add(new AssistantMessage(assistant.getText()));
                }
            } else if (message instanceof ToolResponseMessage toolResponse) {
                List<ToolResponseMessage.ToolResponse> keptResponses = toolResponse.getResponses().stream()
                        .filter(response -> matchedIds.contains(response.id()))
                        .toList();
                if (keptResponses.size() == toolResponse.getResponses().size()) {
                    repaired.add(message);
                } else if (!keptResponses.isEmpty()) {
                    repaired.add(new ToolResponseMessage(keptResponses));
                }
            } else {
                repaired.add(message);
            }
        }
        if (repaired.size() != messages.size()) {
            log.warn("会话记忆存在残缺的工具调用对，已修复 原条数={} 修复后={}", messages.size(), repaired.size());
        }
        return repaired;
    }
}
