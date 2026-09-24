package com.hotel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hotel.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    /** 按天统计会话数（用于近 N 日趋势图） */
    @Select("SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS date, COUNT(*) AS cnt " +
            "FROM chat_session WHERE create_time >= #{start} " +
            "GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d')")
    List<Map<String, Object>> countByDate(@Param("start") LocalDateTime start);

    /**
     * 找出长时间没有消息往来的未结束会话。
     *
     * <p>活性判断取「最后一条消息时间」，没有消息时回退到会话开始时间，
     * 而不是用 chat_session.update_time —— 后者会被状态变更等无关写入刷新。
     * 相关子查询走 chat_message 的 idx_session 索引，外层只扫描少量未结束会话。</p>
     */
    @Select("SELECT s.id FROM chat_session s " +
            "WHERE s.status IN (0, 2) " +
            "  AND COALESCE((SELECT MAX(m.create_time) FROM chat_message m WHERE m.session_id = s.id), " +
            "               s.start_time) < #{cutoff} " +
            "ORDER BY s.id LIMIT #{limit}")
    List<Long> selectIdleSessionIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
