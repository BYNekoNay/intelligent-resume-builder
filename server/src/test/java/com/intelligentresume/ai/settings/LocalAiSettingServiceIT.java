package com.intelligentresume.ai.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.provider.AiRuntimeOverlay;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.config.DesktopModeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地 AI 设置的持久化与校验（R31 / R34）。
 *
 * <p>装配方式与其它桌面 IT 一致（`@SpringBootTest` + `test` profile + 动态 H2 file URL）：
 * 本仓的实体（JSON 列、`NON_KEYWORDS`）在 `@DataJpaTest` 的嵌入式库里建不起来，实测失败过。
 * 凭据存储用假保护器 + 临时目录，因此这条链路在 Linux CI 上也能跑，
 * 不把"Windows 才有 DPAPI"变成"这条链路没有测试"。
 */
@SpringBootTest
@ActiveProfiles("test")
// 服务在测试里是手工 new 的（要注入假保护器），Spring 的 @Transactional 代理不生效，
// 因此派生删除（deleteByUserId）需要测试事务兜底
@Transactional
class LocalAiSettingServiceIT {

    private static final String DATA_DIR = "target/ai-settings-it-" + UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String absolute = Path.of(DATA_DIR).toAbsolutePath().toString().replace('\\', '/');
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + absolute + "/resume"
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,KEY,VALUE,ORDER,DATABASE");
        registry.add("app.desktop.enabled", () -> "true");
        registry.add("app.desktop.data-dir", () -> absolute);
    }

    private static final long USER = 42L;

    @Autowired
    private LocalAiSettingRepository repository;

    @TempDir
    private Path dataDir;

    private LocalAiCredentialStore credentials;
    private LocalAiSettingService service;
    private AiRuntimeOverlay overlay;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        DesktopModeProperties properties = new DesktopModeProperties();
        properties.setDataDir(dataDir.toString());
        credentials = new LocalAiCredentialStore(properties, new AlwaysAvailable());
        overlay = new AiRuntimeOverlay();
        service = new LocalAiSettingService(repository, credentials, new ObjectMapper(), overlay);
    }

    /** 假保护器：把明文反转当密文，足够验证"存的是密文、能读回、能删掉"。 */
    private static final class AlwaysAvailable implements SecretProtector {
        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String name() {
            return "fake-reverse";
        }

        @Override
        public byte[] protect(byte[] plaintext, byte[] entropy) {
            byte[] out = new byte[plaintext.length];
            for (int i = 0; i < plaintext.length; i++) {
                out[i] = plaintext[plaintext.length - 1 - i];
            }
            return out;
        }

        @Override
        public byte[] unprotect(byte[] ciphertext, byte[] entropy) {
            return protect(ciphertext, entropy);
        }
    }

    @Test
    void returnsDefaultsWhenNotConfigured() {
        LocalAiSettingService.View view = service.getOrDefault(USER);
        assertNull(view.id(), "未配置时不应有 id");
        assertEquals("openai-compatible", view.providerCode());
        assertFalse(view.credentialPresent());
        assertTrue(view.credentialStoreAvailable());
        assertTrue(service.get(USER).isEmpty(), "默认值不应落库");
    }

    @Test
    void savesSettingsWithoutKey() {
        LocalAiSettingService.View view = service.save(USER,
                new LocalAiSettingService.UpdateRequest("https://example.test/v1", "qwen-plus", true, null));

        assertEquals("https://example.test/v1", view.baseUrl());
        assertEquals("qwen-plus", view.model());
        assertFalse(view.credentialPresent(), "没传 key 时不应标记为已保存凭据");
        assertEquals(1, repository.findByUserId(USER).stream().count());
    }

    @Test
    void savesKeyEncryptedAndNeverReturnsIt() {
        LocalAiSettingService.View view = service.save(USER,
                new LocalAiSettingService.UpdateRequest("https://example.test/v1", "qwen-plus", true, "sk-abc"));

        assertTrue(view.credentialPresent(), "传了 key 应标记为已保存");
        // 密文真的落盘了，且能解回原值
        assertEquals("sk-abc", credentials.load(USER).orElseThrow());

        // 视图（以及将来照它生成的 JSON）里不能有密钥字段
        List<String> components = Arrays.stream(LocalAiSettingService.View.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertTrue(components.stream().noneMatch(name -> name.toLowerCase().contains("key")),
                "读接口的返回结构里不得出现任何密钥字段，实际：" + components);
    }

    @Test
    void rejectsInvalidBaseUrlAndBlankModel() {
        assertThrows(BusinessException.class, () -> service.save(USER,
                new LocalAiSettingService.UpdateRequest("ftp://example.test", "m", true, null)));
        assertThrows(BusinessException.class, () -> service.save(USER,
                new LocalAiSettingService.UpdateRequest("https://example.test/v1", "  ", true, null)));
    }

    @Test
    void deleteRemovesRowAndCredential() {
        service.save(USER, new LocalAiSettingService.UpdateRequest("https://example.test/v1", "m", true, "sk-1"));
        assertTrue(credentials.exists(USER));

        service.delete(USER);

        assertTrue(service.get(USER).isEmpty(), "设置行应被删除");
        assertFalse(credentials.exists(USER), "加密凭据也要一并删除（R31 的删）");
    }

    @Test
    void testWithoutCredentialFailsWithoutCallingProvider() {
        LocalAiSettingService.TestResult result = service.test(USER, null);
        assertFalse(result.ok());
        assertTrue(result.message().contains("API Key"), "无凭据时要明确提示，实际：" + result.message());
    }

    @Test
    void testConnectionFailureIsRecorded() {
        // 指向一个必然连不上的地址：验证失败会被写回设置行、且不抛异常给调用方
        service.save(USER, new LocalAiSettingService.UpdateRequest("http://127.0.0.1:1/v1", "m", true, "sk-x"));
        LocalAiSettingService.TestResult result = service.test(USER, null);

        assertFalse(result.ok());
        LocalAiSetting saved = repository.findByUserId(USER).orElseThrow();
        assertEquals(Boolean.FALSE, saved.getLastTestOk());
        assertTrue(saved.getLastTestMessage() != null && !saved.getLastTestMessage().isBlank());
        assertTrue(saved.getLastTestedAt() != null);
    }

    @Test
    void apiKeyIsNotWrittenToDatabase() {
        service.save(USER, new LocalAiSettingService.UpdateRequest("https://example.test/v1", "m", true, "sk-zzz"));
        LocalAiSetting saved = repository.findByUserId(USER).orElseThrow();
        // 整行序列化后不得出现密钥明文（表里根本没有密钥列，这条断言防止将来有人"顺手加一列"）
        String row = saved.getId() + saved.getBaseUrl() + saved.getModel() + saved.getProviderCode();
        assertFalse(row.contains("sk-zzz"), "密钥不得出现在设置行里（R34：会进整库备份）");
    }

    @Test
    void savingEnabledSettingPublishesToRuntimeOverlay() {
        service.save(USER, new LocalAiSettingService.UpdateRequest("https://local.test/v1", "my-model", true, "sk-live"));

        assertTrue(overlay.active(), "保存后必须立即生效（否则就是「填了设置但任务还用旧配置」）");
        assertEquals("sk-live", overlay.apiKeyOr("configured-key"));
        assertEquals(List.of("my-model"), overlay.candidatesOr(List.of("qwen-plus", "glm-4")));
    }

    @Test
    void disablingSettingClearsOverlay() {
        service.save(USER, new LocalAiSettingService.UpdateRequest("https://local.test/v1", "m", true, "sk-live"));
        assertTrue(overlay.active());

        service.save(USER, new LocalAiSettingService.UpdateRequest("https://local.test/v1", "m", false, null));

        assertFalse(overlay.active(), "关闭本地设置后应回落到配置文件/环境变量");
    }

    @Test
    void deleteClearsOverlay() {
        service.save(USER, new LocalAiSettingService.UpdateRequest("https://local.test/v1", "m", true, "sk-live"));
        assertTrue(overlay.active());

        service.delete(USER);

        assertFalse(overlay.active());
    }
}
