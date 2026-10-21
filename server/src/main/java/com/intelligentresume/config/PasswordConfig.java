package com.intelligentresume.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 口令散列器（BCrypt）。
 *
 * <p>独立于 {@link SecurityConfig} 是**必需的**，不是风格问题：桌面本地工作区的
 * {@link DesktopLocalUserService} 需要口令散列器来生成随机口令，而若该 Bean 定义在
 * {@code SecurityConfig} 内，就会形成
 * {@code JwtAuthenticationFilter → DesktopLocalUserService → SecurityConfig → JwtAuthenticationFilter}
 * 的循环依赖，Spring Boot 2.6 起默认禁止，容器直接启动失败。
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
