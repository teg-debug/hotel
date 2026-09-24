package com.hotel.vo;

import com.hotel.entity.User;
import lombok.Data;

/**
 * 经营者用户 VO（管理员添加酒店时选择经营者用）
 */
@Data
public class OperatorVO {

    private Long id;
    private String username;
    private String nickname;

    public static OperatorVO from(User user) {
        OperatorVO vo = new OperatorVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        return vo;
    }
}
