package com.intelligentresume.auth.jwt;

import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 解析 {@code Authorization: Bearer <jwt>},写入 {@code request.currentUserId} 与 SecurityContext。
 * 解析失败时不抛出 401,只保留无认证状态——由后续链路在需要时抛 UNAUTHENTICATED。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String CURRENT_USER_ID_ATTRIBUTE = "currentUserId";

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(TokenService tokenService, UserRepository userRepository) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            Long userId = tokenService.parseUserId(token);
            boolean active = userId != null && userRepository.findById(userId)
                    .map(user -> user.getStatus() == User.UserStatus.ACTIVE)
                    .orElse(false);
            if (active) {
                request.setAttribute(CURRENT_USER_ID_ATTRIBUTE, userId);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        userId, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }
}
