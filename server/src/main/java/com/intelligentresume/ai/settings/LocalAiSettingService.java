package com.intelligentresume.ai.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.provider.AiRuntimeOverlay;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 桌面端本地 AI 设置（R31 / R34 / ADR-012 D7-D8）。
 *
 * <p>范围（本批）：设置项增改删查 + **显式**「测试连接」；密钥 DPAPI 加密落在数据目录，
 * 不进工作区库、不进备份与导出包。R32「发送前预览」与 R33「失败保留/降级」不在本类。
 *
 * <p>三条刻意的设计：
 * <ul>
 *   <li><b>密钥永不回传</b>：读接口只返回 {@code credentialPresent}，页面不可能把密钥读出来；</li>
 *   <li><b>测试连接可以带"未保存的" key</b>：用户填完就能先测再存，避免"存了才发现错"；</li>
 *   <li><b>不自动重试、不自动换供应商</b>（D7）：测试失败就把原因写回设置行，由人决定下一步。</li>
 * </ul>
 */
@Service
public class LocalAiSettingService {

    private static final Logger log = LoggerFactory.getLogger(LocalAiSettingService.class);

    /** D7：首版只接 OpenAI Chat Completions 兼容端点。 */
    static final String PROVIDER_OPENAI_COMPATIBLE = "openai-compatible";

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_MODEL = "qwen-plus";

    private final LocalAiSettingRepository repository;
    private final LocalAiCredentialStore credentials;
    private final ObjectMapper objectMapper;
    private final AiRuntimeOverlay runtimeOverlay;

    LocalAiSettingService(LocalAiSettingRepository repository,
                          LocalAiCredentialStore credentials,
                          ObjectMapper objectMapper,
                          AiRuntimeOverlay runtimeOverlay) {
        this.repository = repository;
        this.credentials = credentials;
        this.objectMapper = objectMapper;
        this.runtimeOverlay = runtimeOverlay;
    }

    /** 对外视图：**没有**密钥字段（只有"是否已保存"）。 */
    public record View(Long id,
                       String providerCode,
                       String baseUrl,
                       String model,
                       boolean enabled,
                       boolean credentialPresent,
                       boolean credentialStoreAvailable,
                       String credentialStoreName,
                       String lastTestedAt,
                       Boolean lastTestOk,
                       String lastTestMessage) {
    }

    public record UpdateRequest(String baseUrl, String model, Boolean enabled, String apiKey) {
    }

    public record TestResult(boolean ok, String message, String testedAt) {
    }

    @Transactional(readOnly = true)
    public Optional<View> get(Long userId) {
        return repository.findByUserId(userId).map(this::toView);
    }

    /** 未配置时返回默认值（不落库），让页面有东西可填。 */
    public View getOrDefault(Long userId) {
        return repository.findByUserId(userId).map(this::toView)
                .orElseGet(() -> new View(null, PROVIDER_OPENAI_COMPATIBLE, DEFAULT_BASE_URL, DEFAULT_MODEL,
                        false, credentials.exists(userId), credentials.isAvailable(),
                        credentials.protectorName(), null, null, null));
    }

    @Transactional
    public View save(Long userId, UpdateRequest request) {
        String baseUrl = trimToNull(request.baseUrl());
        String model = trimToNull(request.model());
        if (baseUrl == null || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            throw new BusinessException(ErrorCode.VALIDATION, "baseUrl 必须是 http(s) 地址");
        }
        if (model == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "model 不能为空");
        }

        LocalAiSetting setting = repository.findByUserId(userId).orElseGet(() -> {
            LocalAiSetting created = new LocalAiSetting();
            created.setUserId(userId);
            created.setProviderCode(PROVIDER_OPENAI_COMPATIBLE);
            created.setCreatedAt(LocalDateTime.now());
            return created;
        });
        setting.setBaseUrl(baseUrl);
        setting.setModel(model);
        setting.setEnabled(request.enabled() == null || request.enabled());
        setting.setUpdatedAt(LocalDateTime.now());

        String apiKey = trimToNull(request.apiKey());
        if (apiKey != null) {
            // 密钥写不进去就整体失败：绝不能让行里写 credential_present=true 而磁盘上没有密文
            credentials.save(userId, apiKey);
            setting.setCredentialPresent(true);
        }
        LocalAiSetting saved = repository.save(setting);
        // 保存后立即同步到运行时覆盖 —— 否则"改了设置不生效"（本类存在的意义就在这一步）
        publishToRuntime(saved);
        return toView(saved);
    }

    @Transactional
    public void delete(Long userId) {
        repository.deleteByUserId(userId);
        credentials.delete(userId);
        runtimeOverlay.clear();
        // 刻意不打 userId：LogPrivacyGateTest 禁止日志记录用户标识（排查靠时间点与操作类型即可）
        log.info("本地 AI 设置已删除（含加密凭据）");
    }

    /**
     * 显式「测试连接」（R31：测试必须是用户点出来的动作，且要声明会发请求）。
     *
     * @param apiKeyOverride 允许测试尚未保存的 key；为空则用已保存的密文
     */
    @Transactional
    public TestResult test(Long userId, String apiKeyOverride) {
        LocalAiSetting setting = repository.findByUserId(userId).orElse(null);
        String baseUrl = setting != null ? setting.getBaseUrl() : DEFAULT_BASE_URL;
        String model = setting != null ? setting.getModel() : DEFAULT_MODEL;

        String apiKey = trimToNull(apiKeyOverride);
        if (apiKey == null) {
            apiKey = credentials.load(userId).orElse(null);
        }
        if (apiKey == null) {
            return recordTestResult(setting, false, "尚未保存 API Key");
        }

        TestResult result = callProvider(baseUrl, model, apiKey);
        return recordTestResult(setting, result.ok(), result.message());
    }

    /**
     * 把设置同步到运行时覆盖。
     *
     * <p>三条分支都要覆盖到：未启用 → 清空；启用但解不出密钥 → 清空（宁可回落到配置文件，
     * 也不要用半个配置去请求）；启用且有密钥 → 整份替换。
     */
    public void publishToRuntime(LocalAiSetting setting) {
        if (setting == null || !setting.isEnabled()) {
            runtimeOverlay.clear();
            return;
        }
        String apiKey = credentials.load(setting.getUserId()).orElse(null);
        if (apiKey == null || apiKey.isBlank()) {
            runtimeOverlay.clear();
            return;
        }
        runtimeOverlay.apply(new AiRuntimeOverlay.Snapshot(setting.getBaseUrl(), apiKey, setting.getModel()));
    }

    // ------------------------------------------------------------------ 内部

    private TestResult callProvider(String baseUrl, String model, String apiKey) {
        // 测试用短超时：用户点按钮在等，不能按生产读超时（300s）拖住页面
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30));

        RestClient client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT_ENCODING, "identity")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();

        Map<String, Object> payload = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", "ping")),
                "max_tokens", 1,
                "stream", false);

        try {
            String body = client.post().uri("/chat/completions").body(payload).retrieve().body(String.class);
            boolean parsed = body != null && body.contains("choices");
            return new TestResult(parsed, parsed ? "连接成功（" + model + "）" : "返回内容不是 Chat Completions 结构",
                    now());
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            return new TestResult(false, explainStatus(status), now());
        } catch (Exception exception) {
            // 网络错/超时/证书问题：不透传底层异常文本（可能含 URL 与内部细节），给可读结论
            String type = exception.getClass().getSimpleName();
            return new TestResult(false, "连接失败（" + type + "）：请检查地址与网络", now());
        }
    }

    private String explainStatus(int status) {
        return switch (status) {
            case 401, 403 -> "鉴权失败（HTTP " + status + "）：API Key 无效或无权限";
            case 404 -> "找不到端点（HTTP 404）：baseUrl 应以 /v1 这类版本段结尾";
            case 429 -> "被限流（HTTP 429）：稍后再试或换 key";
            default -> "服务返回 HTTP " + status;
        };
    }

    private TestResult recordTestResult(LocalAiSetting setting, boolean ok, String message) {
        String testedAt = now();
        if (setting != null) {
            setting.setLastTestedAt(LocalDateTime.now());
            setting.setLastTestOk(ok);
            setting.setLastTestMessage(truncate(message));
            setting.setUpdatedAt(LocalDateTime.now());
            repository.save(setting);
        }
        return new TestResult(ok, message, testedAt);
    }

    private View toView(LocalAiSetting setting) {
        return new View(setting.getId(), setting.getProviderCode(), setting.getBaseUrl(), setting.getModel(),
                setting.isEnabled(), setting.isCredentialPresent(),
                credentials.isAvailable(), credentials.protectorName(),
                setting.getLastTestedAt() == null ? null : setting.getLastTestedAt().toString(),
                setting.getLastTestOk(), setting.getLastTestMessage());
    }

    private String now() {
        return LocalDateTime.now().toString();
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
