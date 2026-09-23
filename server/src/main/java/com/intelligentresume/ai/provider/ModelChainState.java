package com.intelligentresume.ai.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型链的有序候选列表与失效冷却状态。
 *
 * <p>背景：百炼的免费额度是**按模型**计量的。单模型硬编码时，只要该模型额度耗尽，
 * 全部 AI 功能一起失败且无法自愈。本类维护「按序尝试、失败顺延」所需的候选顺序
 * 与每个模型的冷却截止时间。
 *
 * <p>两类冷却的语义不同：
 * <ul>
 *   <li><b>配额类</b>（额度耗尽、模型下线）：额度不会在短时间内恢复，用较长的
 *       {@code quotaCooldown}，避免每个请求都白等一轮网络往返。</li>
 *   <li><b>瞬时类</b>（超时、连接失败、5xx、限流、空响应）：用较短的
 *       {@code transientCooldown}，故障恢复后能快速重新参与调度。</li>
 * </ul>
 *
 * <p>线程安全：AI worker 会多线程并发调用，冷却表使用 {@link ConcurrentHashMap}，
 * 不引入全局锁。冷却只影响**候选顺序**，不做硬性阻断 —— {@link #candidates()}
 * 在全部模型都处于冷却期时返回空列表，由调用方决定如何快速失败。
 *
 * <p>冷却状态只存在于进程内存，重启后清空。这是刻意取舍：持久化会引入数据库依赖，
 * 而重启后重新探测一轮的代价有限。
 */
final class ModelChainState {

    private final List<String> models;
    private final Duration quotaCooldown;
    private final Duration transientCooldown;
    private final Clock clock;
    private final Map<String, Instant> cooldownUntil = new ConcurrentHashMap<>();

    ModelChainState(List<String> models, Duration quotaCooldown, Duration transientCooldown, Clock clock) {
        this.models = List.copyOf(models);
        this.quotaCooldown = quotaCooldown;
        this.transientCooldown = transientCooldown;
        this.clock = clock;
    }

    /** 链上全部模型，保持配置顺序；不受冷却影响。 */
    List<String> models() {
        return models;
    }

    /** 链首模型，用于审计字段（实际服务模型由调用日志与指标体现）。 */
    String primary() {
        return models.isEmpty() ? "" : models.get(0);
    }

    int size() {
        return models.size();
    }

    /**
     * 当前可用于调度的模型，保持链序，跳过仍在冷却期的项。
     *
     * @return 未冷却的模型列表；全部处于冷却期时返回空列表
     */
    List<String> candidates() {
        Instant now = clock.instant();
        List<String> ready = new ArrayList<>(models.size());
        for (String model : models) {
            if (!isCoolingDown(model, now)) {
                ready.add(model);
            }
        }
        return ready;
    }

    /** 当前未处于冷却期的模型数量，供健康检查与指标使用。 */
    int availableCount() {
        Instant now = clock.instant();
        int ready = 0;
        for (String model : models) {
            if (!isCoolingDown(model, now)) {
                ready++;
            }
        }
        return ready;
    }

    /** 全部冷却项中最早的恢复时间；没有冷却项时返回 {@code null}。 */
    Instant availableAgainAt() {
        Instant now = clock.instant();
        Instant earliest = null;
        for (String model : models) {
            Instant until = cooldownUntil.get(model);
            if (until != null && until.isAfter(now) && (earliest == null || until.isBefore(earliest))) {
                earliest = until;
            }
        }
        return earliest;
    }

    /** 调用成功：立即清除该模型的冷却，使其回到链首参与调度。 */
    void markSuccess(String model) {
        cooldownUntil.remove(model);
    }

    /** 配额耗尽或模型不可用：进入长冷却。 */
    void markQuotaExhausted(String model) {
        cooldownUntil.put(model, clock.instant().plus(quotaCooldown));
    }

    /**
     * 瞬时故障：进入短冷却。
     *
     * <p>若该模型已有更晚的冷却截止时间（例如刚被标记为配额耗尽），保留更晚的那个，
     * 避免短冷却把长冷却意外缩短。
     */
    void markTransientFailure(String model) {
        Instant candidate = clock.instant().plus(transientCooldown);
        cooldownUntil.merge(model, candidate,
                (existing, proposed) -> existing.isAfter(proposed) ? existing : proposed);
    }

    private boolean isCoolingDown(String model, Instant now) {
        Instant until = cooldownUntil.get(model);
        return until != null && until.isAfter(now);
    }
}
