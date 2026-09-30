package com.intelligentresume.auth.service;

import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * 用户状态短 TTL 缓存测试（PA-2）。使用可变时钟，不依赖真实时间流逝。
 */
class ActiveUserCacheTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    /** 可推进的测试时钟，避免测试里 sleep。 */
    private static final class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-09-30T10:00:00Z");

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final UserRepository userRepository = mock(UserRepository.class);

    private ActiveUserCache cache(int maxEntries) {
        return new ActiveUserCache(userRepository, TTL, clock, maxEntries);
    }

    private User user(long id, User.UserStatus status) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setStatus(status);
        return user;
    }

    @Test
    @DisplayName("TTL 内命中缓存只查一次库；过期后重新回源")
    void isActive_cachesWithinTtl_andReloadsAfterExpiry() {
        ActiveUserCache cache = cache(ActiveUserCache.DEFAULT_MAX_ENTRIES);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, User.UserStatus.ACTIVE)));

        assertTrue(cache.isActive(1L));
        assertTrue(cache.isActive(1L));
        verify(userRepository, times(1)).findById(1L);

        // TTL 边界内仍命中缓存
        clock.advance(TTL.minusMillis(1));
        assertTrue(cache.isActive(1L));
        verify(userRepository, times(1)).findById(1L);

        // 超过 TTL 后回源（此时变为 DISABLED → 返回 false 且重新缓存）
        clock.advance(Duration.ofMillis(2));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, User.UserStatus.DISABLED)));
        assertFalse(cache.isActive(1L));
        assertFalse(cache.isActive(1L));
        verify(userRepository, times(2)).findById(1L);
    }

    @Test
    @DisplayName("负结果（不存在/非 ACTIVE）同样缓存，避免无效令牌反复回源")
    void isActive_cachesNegativeResults() {
        ActiveUserCache cache = cache(ActiveUserCache.DEFAULT_MAX_ENTRIES);
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertFalse(cache.isActive(99L));
        assertFalse(cache.isActive(99L));
        verify(userRepository, times(1)).findById(99L);
    }

    @Test
    @DisplayName("evict 后立即回源：删号路径提交后清除缓存即可即时失效")
    void evict_forcesReload() {
        ActiveUserCache cache = cache(ActiveUserCache.DEFAULT_MAX_ENTRIES);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, User.UserStatus.ACTIVE)));
        assertTrue(cache.isActive(1L));

        cache.evict(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, User.UserStatus.DISABLED)));
        assertFalse(cache.isActive(1L));
        verify(userRepository, times(2)).findById(1L);
    }

    @Test
    @DisplayName("容量兜底：超过 maxEntries 时清理过期条目，不无限增长")
    void isActive_overCapacity_prunesExpiredEntries() {
        ActiveUserCache cache = cache(2);
        when(userRepository.findById(anyLong()))
                .thenAnswer(invocation -> Optional.of(
                        user(invocation.getArgument(0, Long.class), User.UserStatus.ACTIVE)));

        cache.isActive(1L);
        cache.isActive(2L);
        clock.advance(TTL.plusSeconds(1));
        // 第 3 个键触发惰性清理：前两条已过期被移除，重新回源
        cache.isActive(3L);
        cache.isActive(1L);

        verify(userRepository, times(2)).findById(1L);
    }
}