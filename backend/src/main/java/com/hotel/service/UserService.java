package com.hotel.service;

import com.hotel.dto.ChangePasswordDTO;
import com.hotel.dto.LoginDTO;
import com.hotel.dto.RegisterDTO;
import com.hotel.vo.LoginVO;
import com.hotel.vo.UserInfoVO;

/**
 * 用户中心：注册 / 登录 / 登出 / 个人信息与密码
 */
public interface UserService {

    /** 注册（密码 BCrypt 加密） */
    void register(RegisterDTO dto);

    /** 登录：校验密码，签发 JWT 并写入 Redis */
    LoginVO login(LoginDTO dto);

    /** 登出：删除 Redis 登录态，JWT 立即失效 */
    void logout();

    /** 当前登录用户信息（含明文密码，演示用） */
    UserInfoVO getMe();

    /** 修改本人密码（校验原密码） */
    void changePassword(ChangePasswordDTO dto);
}
