package com.hotel.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.common.Result;
import com.hotel.common.ResultCode;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 认证拦截器：
 * - 解析 Authorization: Bearer xxx
 * - 与 Redis 中登录时写入的 token 比对（支持登出失效、单端登录）
 * - 校验通过后写入 UserContext
 *
 * <p>每请求一次 Redis 往返在接口层是笔可观的开销，因此校验通过后会短期记在
 * {@link TokenCache} 里；该缓存默认关闭（TTL=0），开启时本实例内免去逐请求查 Redis，
 * 代价是跨实例的登出/互踢最多滞后 TTL，详见 TokenCache 的说明。</p>
 */
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private static final String LOGIN_TOKEN_KEY = "auth:token:";

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final TokenCache tokenCache;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 非 Controller 方法（如静态资源）直接放行
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        String token = resolveToken(request);
        if (StringUtils.hasText(token)) {
            try {
                Claims claims = jwtUtil.parseToken(token);
                Long userId = Long.valueOf(claims.getSubject());
                if (tokenCache.matches(userId, token) || matchesInRedis(userId, token)) {
                    UserContext.set(userId,
                            claims.get("username", String.class),
                            claims.get("role", Integer.class));
                    return true;
                }
            } catch (Exception ignored) {
                // Token 无效 / 过期，统一按未登录处理
            }
        }
        writeUnauthorized(response);
        return false;
    }

    /** 与 Redis 中的登录态比对；通过则顺手记住，后续请求可省掉这次往返 */
    private boolean matchesInRedis(Long userId, String token) {
        String cached = stringRedisTemplate.opsForValue().get(LOGIN_TOKEN_KEY + userId);
        boolean matched = token.equals(cached);
        if (matched) {
            tokenCache.remember(userId, token);
        }
        return matched;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(ResultCode.UNAUTHORIZED)));
    }
}
