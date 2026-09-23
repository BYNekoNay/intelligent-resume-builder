package com.intelligentresume.ai.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型链状态容器测试。使用可变时钟，不依赖真实时间流逝。
 */
class ModelChainStateTest {

    private static final Duration QUOTA_COOLDOWN = Duration.ofSeconds(1800);
    private static final Duration TRANSIENT_COOLDOWN = Duration.ofSeconds(60);

    /** 可推进的测试时钟，避免测试里 sleep。 */
    private static final class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-09-23T12:00:00Z");

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

    private ModelChainState chain(String... models) {
        return new ModelChainState(List.of(models), QUOTA_COOLDOWN, TRANSIENT_COOLDOWN, clock);
    }

    @Test
    @DisplayName("初始状态：全部模型都是候选，且保持配置顺序")
    void allModelsCandidateInitially() {
        ModelChainState state = chain("m1", "m2", "m3");

        assertEquals(List.of("m1", "m2", "m3"), state.candidates());
        assertEquals(3, state.availableCount());
        assertEquals(3, state.size());
        assertEquals("m1", state.primary());
        assertNull(state.availableAgainAt());
    }

    @Test
    @DisplayName("配额耗尽：该模型被移出候选，其余顺序不变")
    void quotaExhaustedRemovesFromCandidates() {
        ModelChainState state = chain("m1", "m2", "m3");

        state.markQuotaExhausted("m1");

        assertEquals(List.of("m2", "m3"), state.candidates());
        assertEquals(2, state.availableCount());
        // 链本身不改变，仅冷却影响候选
        assertEquals(List.of("m1", "m2", "m3"), state.models());
    }

    @Test
    @DisplayName("调用成功：立即清除冷却，回到候选首位")
    void successClearsCooldown() {
        ModelChainState state = chain("m1", "m2");
        state.markQuotaExhausted("m1");
        assertEquals(List.of("m2"), state.candidates());

        state.markSuccess("m1");

        assertEquals(List.of("m1", "m2"), state.candidates());
    }

    @Test
    @DisplayName("配额冷却到期后自动恢复参与调度")
    void quotaCooldownExpires() {
        ModelChainState state = chain("m1", "m2");
        state.markQuotaExhausted("m1");
        assertFalse(state.candidates().contains("m1"));

        clock.advance(QUOTA_COOLDOWN.minusSeconds(1));
        assertFalse(state.candidates().contains("m1"), "未到期不应恢复");

        clock.advance(Duration.ofSeconds(2));
        assertTrue(state.candidates().contains("m1"), "到期后应恢复");
    }

    @Test
    @DisplayName("瞬时故障使用短冷却，恢复快于配额冷却")
    void transientCooldownIsShorter() {
        ModelChainState state = chain("m1", "m2");
        state.markTransientFailure("m1");

        clock.advance(TRANSIENT_COOLDOWN.plusSeconds(1));

        assertEquals(List.of("m1", "m2"), state.candidates());
    }

    @Test
    @DisplayName("瞬时故障不会缩短已存在的更长冷却")
    void transientFailureDoesNotShortenQuotaCooldown() {
        ModelChainState state = chain("m1", "m2");
        state.markQuotaExhausted("m1");
        state.markTransientFailure("m1");

        clock.advance(TRANSIENT_COOLDOWN.plusSeconds(1));

        assertFalse(state.candidates().contains("m1"),
                "配额冷却（1800s）不应被随后的瞬时冷却（60s）缩短");
    }

    @Test
    @DisplayName("全部模型冷却时候选为空，并给出最早的恢复时间")
    void allCoolingDownReturnsEmptyCandidates() {
        ModelChainState state = chain("m1", "m2");
        state.markTransientFailure("m1");
        clock.advance(Duration.ofSeconds(10));
        state.markQuotaExhausted("m2");

        assertTrue(state.candidates().isEmpty());
        assertEquals(0, state.availableCount());
        assertNotNull(state.availableAgainAt());
    }

    @Test
    @DisplayName("多模型部分冷却：availableAgainAt 取最早恢复时间")
    void availableAgainAtReturnsEarliest() {
        ModelChainState state = chain("m1", "m2");
        state.markTransientFailure("m1");
        clock.advance(Duration.ofSeconds(5));
        state.markQuotaExhausted("m2");

        Instant earliest = state.availableAgainAt();
        Instant transientExpiry = Instant.parse("2026-09-23T12:00:00Z")
                .plus(TRANSIENT_COOLDOWN);
        assertEquals(transientExpiry, earliest);
    }

    @Test
    @DisplayName("单元素链退化为单模型：冷却后无候选")
    void singleModelChainBehaviour() {
        ModelChainState state = chain("only-model");
        assertEquals(List.of("only-model"), state.candidates());

        state.markQuotaExhausted("only-model");

        assertTrue(state.candidates().isEmpty());
        assertEquals(0, state.availableCount());
    }

    @Test
    @DisplayName("空链不抛异常，候选为空")
    void emptyChainIsSafe() {
        ModelChainState state = chain();

        assertTrue(state.models().isEmpty());
        assertTrue(state.candidates().isEmpty());
        assertEquals(0, state.availableCount());
        assertEquals("", state.primary());
        assertNull(state.availableAgainAt());
    }
}
