package com.hotel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hotel.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UserMapper extends BaseMapper<User> {

    /**
     * 查询某酒店在转人工时需要通知的账号：该酒店绑定的前台，以及该酒店的经营者。
     * 用于把转人工通知投递到具体用户的点对点队列，而不是广播给所有连接。
     */
    @Select("SELECT id FROM `user` WHERE status = 1 AND (" +
            "  (role = 3 AND hotel_id = #{hotelId})" +
            "  OR id = (SELECT owner_id FROM hotel WHERE id = #{hotelId})" +
            ")")
    List<Long> selectHotelNotifierIds(@Param("hotelId") Long hotelId);
}
