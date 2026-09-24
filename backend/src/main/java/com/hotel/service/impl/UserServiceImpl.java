package com.hotel.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.common.BusinessException;
import com.hotel.common.ResultCode;
import com.hotel.dto.ChangePasswordDTO;
import com.hotel.dto.LoginDTO;
import com.hotel.dto.RegisterDTO;
import com.hotel.entity.User;
import com.hotel.mapper.UserMapper;
import com.hotel.security.JwtUtil;
import com.hotel.security.TokenCache;
import com.hotel.security.UserContext;
import com.hotel.service.UserService;
import com.hotel.vo.LoginVO;
import com.hotel.vo.UserInfoVO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final String LOGIN_TOKEN_KEY = "auth:token:";

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;
    private final TokenCache tokenCache;

    @Override
    public void register(RegisterDTO dto) {
        // 用户名唯一性校验
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));
        if (count > 0) {
            throw new BusinessException("用户名已存在");
        }
        User user = new User();
        user.setUsername(dto.getUsername());
        // BCrypt 加密存储，不保存明文
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname() : dto.getUsername());
        user.setPhone(dto.getPhone());
        user.setMemberLevel(0); // 默认普通会员
        user.setRole(0);        // 默认普通用户
        user.setStatus(1);
        userMapper.insert(user);
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));
        // 统一提示，避免暴露"用户不存在"信息
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() == 0) {
            throw new BusinessException("账号已被禁用");
        }
        // 签发 JWT 并写入 Redis（拦截器据此校验：登出即失效、单端登录互踢）
        String token = jwtUtil.createToken(user.getId(), user.getUsername(), user.getRole());
        stringRedisTemplate.opsForValue().set(
                LOGIN_TOKEN_KEY + user.getId(), token, jwtUtil.getExpire(), TimeUnit.SECONDS);
        // 本次登录换了新 token，本地缓存里的旧凭据必须立刻作废，
        // 否则开启本地缓存时新 token 会在 TTL 内被误判为无效
        tokenCache.forget(user.getId());
        return new LoginVO(token, UserInfoVO.from(user));
    }

    @Override
    public void logout() {
        Long userId = UserContext.getUserId();
        if (userId != null) {
            stringRedisTemplate.delete(LOGIN_TOKEN_KEY + userId);
            // 同实例立即失效；跨实例最迟在本地缓存 TTL 到期后失效
            tokenCache.forget(userId);
        }
    }

    @Override
    public UserInfoVO getMe() {
        User user = userMapper.selectById(UserContext.getUserId());
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return UserInfoVO.from(user);
    }

    @Override
    public void changePassword(ChangePasswordDTO dto) {
        User user = userMapper.selectById(UserContext.getUserId());
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        // 校验原密码
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            throw new BusinessException("原密码不正确");
        }
        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        userMapper.updateById(user);
    }
}
