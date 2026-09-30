package com.intelligentresume.auth.service;

import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「用户是否为 ACTIVE」的短 TTL 进程内缓存（09-26 报告 PA-2）。
 *
 * <p>背景：{@code JwtAuthenticationFilter} 为落实 #6 的安全闭环（删号即失效），
 * 每个携带 Bearer 的请求都要查一次 user 表校验 ACTIVE。本缓存把该查询降到
 * 每用户 ≤1 次/TTL，用有界的失效延迟换取每请求的数据库往返。
 *
 * <p>失效语义（与 #6 的「删号即失效」口径对齐）：
 * <ul>
 *     <li>{@code AuthService.deleteAccount} 在事务提交后显式 {@link #evict}：
 *     删号对后续请求立即可见，不等 TTL；</li>
 *     <li>其它途径的状态变更（直改库、未来新增的管理入口、多实例部署的其它节点）
 *     最迟 TTL 后生效——缓存是进程内的，不做跨实例失效，这是既定取舍；</li>
 *     <li>负结果（不存在/非 ACTIVE）同样缓存，避免无效令牌反复回源。</li>
 * </ul>
 *
 * <p>只缓存 boolean，不缓存 User 实体，避免把可变用户数据引入一致性问题。
 * 容量兜底：仅在条目数超过 {@link #DEFAULT_MAX_ENTRIES} 时惰性清理过期项。
 */
@Component
public class ActiveUserCache {

    /** 常驻条目上限的兜底阈值：条目数 ≈ 活跃令牌持有者数，正常远低于此值。 */
    static final int DEFAULT_MAX_ENTRIES = 10_000;

    private final UserRepository userRepository;
    private final long ttlMillis;
    private final Clock clock;
    private final int maxEntries;
    private final ConcurrentHashMap<Long, CachedStatus> entries = new ConcurrentHashMap<>();

    @Autowired
    public ActiveUserCache(UserRepository userRepository,
                           @Value("${app.jwt.active-user-cache-ttl-seconds:30}") long ttlSeconds) {
        this(userRepository, Duration.ofSeconds(ttlSeconds), Clock.systemUTC(), DEFAULT_MAX_ENTRIES);
    }

    /** 测试用：可控时钟与容量，避免依赖真实时间流逝。 */
    ActiveUserCache(UserRepository userRepository, Duration ttl, Clock clock, int maxEntries) {
        this.userRepository = userRepository;
        this.ttlMillis = ttl.toMillis();
        this.clock = clock;
        this.maxEntries = maxEntries;
    }

    /**
     * 用户当前是否 ACTIVE（不存在、已停用、已删号均为 false）。结果按 TTL 缓存。
     */
    public boolean isActive(Long userId) {
        long now = clock.millis();
        CachedStatus cached = entries.get(userId);
        if (cached != null && cached.expiresAtMillis > now) {
            return cached.active;
        }
        boolean active = userRepository.findById(userId)
                .map(user -> user.getStatus() == User.UserStatus.ACTIVE)
                .orElse(false);
        entries.put(userId, new CachedStatus(active, now + ttlMillis));
        evictExpiredIfOverCapacity(now);
        return active;
    }

    /** 状态变更后强制回源；删号路径在事务提交后调用，保证「删号即失效」。 */
    public void evict(Long userId) {
        entries.remove(userId);
    }

    private void evictExpiredIfOverCapacity(long now) {
        if (entries.size() <= maxEntries) {
            return;
        }
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis <= now);
    }

    private record CachedStatus(boolean active, long expiresAtMillis) {
    }
}