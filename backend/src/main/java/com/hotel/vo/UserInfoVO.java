package com.hotel.vo;

import com.hotel.entity.User;
import lombok.Data;

/**
 * 用户信息 VO（不暴露 password）
 */
@Data
public class UserInfoVO {

    private Long id;
    private String username;
    private String nickname;
    private String phone;
    private String email;
    private String avatar;
    private Integer memberLevel;
    private Integer role;
    /** 前台账号绑定的酒店ID（role=3 时有效） */
    private Long hotelId;

    public static UserInfoVO from(User user) {
        UserInfoVO vo = new UserInfoVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setAvatar(user.getAvatar());
        vo.setMemberLevel(user.getMemberLevel());
        vo.setRole(user.getRole());
        vo.setHotelId(user.getHotelId());
        return vo;
    }
}
