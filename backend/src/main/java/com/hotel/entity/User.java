package com.hotel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表
 */
@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 用户名（登录账号） */
    private String username;
    /** 密码（BCrypt 加密，不存明文） */
    private String password;
    private String nickname;
    private String phone;
    private String email;
    private String avatar;
    /** 会员等级：0-普通 1-银卡 2-金卡 */
    private Integer memberLevel;
    /** 角色：0-普通用户 1-酒店经营者 2-管理员 3-酒店前台 */
    private Integer role;
    /** 前台账号绑定的酒店ID（role=3 时有效） */
    private Long hotelId;
    /** 状态：0-禁用 1-正常 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
