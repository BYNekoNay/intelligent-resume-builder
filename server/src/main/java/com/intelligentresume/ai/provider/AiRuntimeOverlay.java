package com.intelligentresume.ai.provider;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * 运行时 AI 配置覆盖（ADR-012 阶段五的「设置生效」部分）。
 *
 * <p>为什么需要它：{@link BailianAiProvider} 的 `apiKey` / `baseUrl` / `model` 都是**构造期**
 * 注入的 final 值（`RestClient` 的 `Authorization` 头也是构造时固定），所以"用户在设置里换了 key
 * 立刻生效"在当时的结构下做不到。这里放一个可替换的快照，provider 每次调用时读一次。
 *
 * <p>三条刻意约束：
 * <ul>
 *   <li><b>默认空</b>：在线部署永远拿到 empty，行为与改造前逐字节一致；</li>
 *   <li><b>只有单 endpoint + model + key</b>（ADR-012 D7）—— 不把模型链搬进来，
 *       链的冷却/预算状态仍归 {@code ModelChainState}；</li>
 *   <li><b>不可变快照 + volatile 换引用</b>：读方不加锁，也不会看到"半新半旧"的配置。</li>
 * </ul>
 *
 * <p>解析逻辑（"覆盖生效就用覆盖值，否则用传入的兜底"）放在本类而不是 provider 里：
 * provider 踩着行数棘轮（只许下调），而这些是纯分支判断，放这里还能单独测。
 */
@Component
public class AiRuntimeOverlay {

    /** 一次覆盖的全部内容；不可变。 */
    public record Snapshot(String baseUrl, String apiKey, String model) {

        public boolean usable() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    private volatile Snapshot current;

    /** 覆盖生效时按快照缓存客户端：只有换设置才重建，避免每次调用都 new。 */
    private volatile Snapshot cachedSnapshot;
    private volatile RestClient cachedClient;

    public Optional<Snapshot> current() {
        Snapshot snapshot = current;
        return snapshot != null && snapshot.usable() ? Optional.of(snapshot) : Optional.empty();
    }

    public void apply(Snapshot snapshot) {
        this.current = snapshot != null && snapshot.usable() ? snapshot : null;
    }

    public void clear() {
        this.current = null;
    }

    /** 当前是否处于"用户自定义配置"状态（日志与测试用）。 */
    public boolean active() {
        return current().isPresent();
    }

    // ------------------------------------------------------------------ 供 provider 调用

    public String apiKeyOr(String fallback) {
        return current().map(Snapshot::apiKey).orElse(fallback);
    }

    /**
     * 覆盖生效时**只剩用户指定的那一个模型**（D7：单 endpoint + model + key）。
     *
     * <p>刻意不把配置里的模型链混进来：否则"改了设置"看起来生效、实际还在用旧链。
     */
    public List<String> candidatesOr(List<String> fallback) {
        Optional<Snapshot> snapshot = current();
        if (snapshot.isEmpty()) {
            return fallback;
        }
        List<String> single = List.of(snapshot.get().model());
        return single;
    }

    /** 覆盖生效时返回按快照重建的客户端（baseUrl 与 Authorization 都在里面）。 */
    public RestClient clientOr(RestClient fallback, BiFunction<String, String, RestClient> factory) {
        Optional<Snapshot> snapshot = current();
        if (snapshot.isEmpty()) {
            return fallback;
        }
        Snapshot target = snapshot.get();
        RestClient cached = cachedClient;
        if (cached != null && target.equals(cachedSnapshot)) {
            return cached;
        }
        RestClient built = factory.apply(target.baseUrl(), target.apiKey());
        cachedClient = built;
        cachedSnapshot = target;
        return built;
    }
}
