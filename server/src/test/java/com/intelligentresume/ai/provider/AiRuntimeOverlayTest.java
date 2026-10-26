package com.intelligentresume.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.common.observability.AppObservability;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行时覆盖的解析规则（ADR-012 阶段五「设置生效」）。
 *
 * <p>这个类守的是"改了设置到底有没有作用"：
 * <ul>
 *   <li>没有覆盖时**必须**原样返回兜底值（在线部署与改造前逐字节一致）；</li>
 *   <li>有覆盖时 key / 单模型 / 客户端全部替换；</li>
 *   <li>key 为空的快照视为无效 —— 否则会出现"覆盖生效但 key 是空的"，请求必然 401。</li>
 * </ul>
 */
class AiRuntimeOverlayTest {

    private static final List<String> CONFIG_CHAIN = List.of("qwen-plus", "glm-4", "kimi-k3");

    @Test
    void emptyOverlayFallsBackToConfiguredValues() {
        AiRuntimeOverlay overlay = new AiRuntimeOverlay();
        assertFalse(overlay.active());
        assertEquals("cfg-key", overlay.apiKeyOr("cfg-key"));
        assertEquals(CONFIG_CHAIN, overlay.candidatesOr(CONFIG_CHAIN), "无覆盖时必须原样返回配置链");

        RestClient fallback = RestClient.create();
        assertSame(fallback, overlay.clientOr(fallback, (url, key) -> RestClient.create()));
    }

    @Test
    void appliedOverlayReplacesKeyAndCollapsesChainToSingleModel() {
        AiRuntimeOverlay overlay = new AiRuntimeOverlay();
        overlay.apply(new AiRuntimeOverlay.Snapshot("https://local.test/v1", "sk-local", "my-model"));

        assertTrue(overlay.active());
        assertEquals("sk-local", overlay.apiKeyOr("cfg-key"));
        assertEquals(List.of("my-model"), overlay.candidatesOr(CONFIG_CHAIN),
                "覆盖生效时只能用用户那一个模型（D7），不能继续沿用配置链");
    }

    @Test
    void snapshotWithoutKeyIsIgnored() {
        AiRuntimeOverlay overlay = new AiRuntimeOverlay();
        overlay.apply(new AiRuntimeOverlay.Snapshot("https://local.test/v1", "  ", "m"));

        assertFalse(overlay.active(), "key 为空的快照必须视为无效，否则请求必然 401");
        assertEquals("cfg-key", overlay.apiKeyOr("cfg-key"));
    }

    @Test
    void clearRestoresConfiguredBehaviour() {
        AiRuntimeOverlay overlay = new AiRuntimeOverlay();
        overlay.apply(new AiRuntimeOverlay.Snapshot("https://local.test/v1", "sk-local", "m"));
        overlay.clear();

        assertFalse(overlay.active());
        assertEquals(CONFIG_CHAIN, overlay.candidatesOr(CONFIG_CHAIN));
    }

    @Test
    void clientIsRebuiltOnlyWhenSnapshotChanges() {
        AiRuntimeOverlay overlay = new AiRuntimeOverlay();
        AtomicInteger builds = new AtomicInteger();
        RestClient fallback = RestClient.create();

        overlay.apply(new AiRuntimeOverlay.Snapshot("https://a.test/v1", "k1", "m1"));
        RestClient first = overlay.clientOr(fallback, (url, key) -> {
            builds.incrementAndGet();
            return RestClient.create();
        });
        RestClient second = overlay.clientOr(fallback, (url, key) -> {
            builds.incrementAndGet();
            return RestClient.create();
        });
        assertSame(first, second, "同一快照应命中缓存，不能每次调用都重建客户端");
        assertEquals(1, builds.get());

        overlay.apply(new AiRuntimeOverlay.Snapshot("https://b.test/v1", "k2", "m2"));
        RestClient third = overlay.clientOr(fallback, (url, key) -> {
            builds.incrementAndGet();
            return RestClient.create();
        });
        assertEquals(2, builds.get(), "换设置必须重建客户端（baseUrl 与 Authorization 都变了）");
        assertNotSameIdentity(first, third);
    }

    private void assertNotSameIdentity(Object a, Object b) {
        assertFalse(a == b, "换了快照后不应复用旧客户端");
    }

    // ------------------------------------------------------------------ provider 级

    /**
     * 光测 overlay 不够 —— 要证明**provider 真的读它**。
     * 这里构造真实的 provider（不联网），断言模型列表与可用性都跟着覆盖走。
     */
    @Test
    void providerReadsTheOverlay() {
        BailianAiProvider provider = new BailianAiProvider(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "",                       // 故意留空：证明"没配 key 也能用用户设置跑起来"
                "fallback-model",
                "cfg-model-a,cfg-model-b",
                10, 60, 1800, 60, 600, "JOB_GENERATION",
                new ObjectMapper(), null, new FailureCategoryClassifier());
        provider.setRuntimeOverlay(new AiRuntimeOverlay());
        assertEquals(List.of("cfg-model-a", "cfg-model-b"), provider.modelChain(),
                "覆盖为空时应原样读配置里的模型链（不是 fallback 单模型）");

        AiRuntimeOverlay overlay = new AiRuntimeOverlay();
        overlay.apply(new AiRuntimeOverlay.Snapshot("https://local.test/v1", "sk-local", "local-model"));
        provider.setRuntimeOverlay(overlay);

        assertEquals(List.of("local-model"), provider.modelChain(),
                "覆盖生效后模型列表只能有用户那一个（D7）");
        assertEquals("local-model", provider.modelCode(), "审计字段（modelCode）也要跟着覆盖走");
        assertTrue(provider.isAvailable(), "用户设置了 key 之后必须被视为可用");
    }
}
