package com.hotel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.common.BusinessException;
import com.hotel.entity.Hotel;
import com.hotel.entity.User;
import com.hotel.mapper.HotelMapper;
import com.hotel.mapper.UserMapper;
import com.hotel.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 酒店维度的数据权限判定。
 *
 * <p>把原先分散在工单模块里的判定逻辑收敛到一处，客服会话、知识库等模块共用同一口径，
 * 避免有的模块做了租户隔离、有的没做。</p>
 *
 * <p>角色约定：2-系统管理员（全量）；1-经营者（名下酒店）；3-酒店前台（绑定酒店）。</p>
 */
@Component
@RequiredArgsConstructor
public class HotelScopeService {

    private final HotelMapper hotelMapper;
    private final UserMapper userMapper;

    /** 是否为系统管理员 */
    public boolean isSysAdmin(Integer role) {
        return role != null && role == 2;
    }

    /** 校验当前用户是否有权访问该酒店，管理员放行 */
    public void checkHotelAccess(Long hotelId, Integer role) {
        if (isSysAdmin(role)) {
            return;
        }
        if (role == null || role < 1) {
            throw new BusinessException("无权限访问该酒店数据");
        }
        if (hotelId == null) {
            throw new BusinessException("缺少酒店归属，无法确认访问权限");
        }
        Long userId = UserContext.getUserId();
        if (role == 3) {
            User staff = userMapper.selectById(userId);
            if (staff == null || staff.getHotelId() == null || !staff.getHotelId().equals(hotelId)) {
                throw new BusinessException("仅能访问自己绑定酒店的数据");
            }
            return;
        }
        Long count = hotelMapper.selectCount(new LambdaQueryWrapper<Hotel>()
                .eq(Hotel::getId, hotelId).eq(Hotel::getOwnerId, userId));
        if (count == null || count == 0) {
            throw new BusinessException("无权访问该酒店的数据");
        }
    }

    /**
     * 校验客服会话的访问权限。
     *
     * <p>会话的 {@code hotelId} 为空表示平台级咨询，不属于任何酒店，
     * 因此只对系统管理员开放，避免把不属于自己的会话暴露给经营者。</p>
     */
    public void checkSessionAccess(Long sessionHotelId, Integer role) {
        if (isSysAdmin(role)) {
            return;
        }
        if (sessionHotelId == null) {
            throw new BusinessException("该会话不属于您名下的酒店，无权访问");
        }
        checkHotelAccess(sessionHotelId, role);
    }

    /**
     * 当前角色可见的酒店ID列表。
     *
     * @return 管理员返回 {@code null} 表示不限制；其余角色返回其可见酒店集合，可能为空
     */
    public List<Long> allowedHotelIds(Integer role) {
        if (isSysAdmin(role)) {
            return null;
        }
        if (role == null || role < 1) {
            return List.of();
        }
        Long userId = UserContext.getUserId();
        if (role == 3) {
            User staff = userMapper.selectById(userId);
            return staff != null && staff.getHotelId() != null ? List.of(staff.getHotelId()) : List.of();
        }
        return hotelMapper.selectList(new LambdaQueryWrapper<Hotel>().eq(Hotel::getOwnerId, userId))
                .stream().map(Hotel::getId).toList();
    }
}
