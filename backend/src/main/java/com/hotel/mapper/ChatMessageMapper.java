package com.hotel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hotel.entity.ChatMessage;
import com.hotel.vo.HotQuestionVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /**
     * 热问榜：按用户提问原文聚合统计。
     *
     * <p>取 {@code sender = 0}（用户）的消息，按内容分组统计提问次数，
     * 并统计其中最终转人工的会话数——转人工比例高说明这条问题知识库没接住。
     * 时间窗口过滤依赖 {@code idx_sender_time} 索引。</p>
     *
     * @param hotelIds 可见的酒店ID集合；为 null 表示不限制（系统管理员）
     */
    @Select("<script>" +
            "SELECT m.content AS question, MAX(m.intent_tag) AS category, " +
            "       COUNT(*) AS ask_count, " +
            "       COUNT(DISTINCT CASE WHEN s.transfer_flag = 1 THEN m.session_id END) AS transfer_count " +
            "FROM chat_message m JOIN chat_session s ON s.id = m.session_id " +
            "WHERE m.sender = 0 AND m.create_time >= #{start} " +
            "<if test='hotelIds != null'>" +
            "AND s.hotel_id IN " +
            "<foreach collection='hotelIds' item='hid' open='(' separator=',' close=')'>#{hid}</foreach> " +
            "</if>" +
            "GROUP BY m.content ORDER BY ask_count DESC LIMIT #{limit}" +
            "</script>")
    List<HotQuestionVO> topUserQuestions(@Param("start") LocalDateTime start,
                                        @Param("limit") int limit,
                                        @Param("hotelIds") List<Long> hotelIds);
}
