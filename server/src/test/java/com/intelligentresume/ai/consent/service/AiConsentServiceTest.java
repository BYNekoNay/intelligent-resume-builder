package com.intelligentresume.ai.consent.service;

import com.intelligentresume.ai.consent.domain.AiConsent;
import com.intelligentresume.ai.consent.domain.ConsentStatus;
import com.intelligentresume.ai.consent.dto.ConsentResponse;
import com.intelligentresume.ai.consent.dto.GrantConsentRequest;
import com.intelligentresume.ai.consent.repository.AiConsentRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AiConsentService 单元测试（Mockito）。
 * 覆盖事件溯源模型:授权、撤回、历史保留、版本校验。
 */
@ExtendWith(MockitoExtension.class)
class AiConsentServiceTest {

    @Mock private AiConsentRepository repository;
    @Captor private ArgumentCaptor<AiConsent> captor;

    private AiConsentService service;

    private static final String POLICY_VERSION = "v1.1.0";

    @BeforeEach
    void setUp() {
        service = new AiConsentService(repository);
        ReflectionTestUtils.setField(service, "policyVersion", POLICY_VERSION);
    }

    @Test
    @DisplayName("授权后 current 返回 GRANTED")
    void grant_thenCurrent_returnsGranted() {
        when(repository.save(any(AiConsent.class))).thenAnswer(inv -> {
            AiConsent c = inv.getArgument(0);
            c.setId(1L);
            c.setCreatedAt(LocalDateTime.now());
            return c;
        });

        GrantConsentRequest req = new GrantConsentRequest(
                POLICY_VERSION, "bailian",
                List.of("JOB_GENERATION"), List.of("resume"), "hash123");

        ConsentResponse response = service.grant(req, 100L);

        assertEquals(ConsentStatus.GRANTED, response.status());
        assertEquals(POLICY_VERSION, response.policyVersion());
        assertEquals("bailian", response.providerCode());
        assertEquals(List.of("JOB_GENERATION"), response.taskScopes());
        assertEquals(List.of("resume"), response.dataCategories());
    }

    @Test
    @DisplayName("policyVersion 不匹配抛 VALIDATION")
    void grant_wrongPolicyVersion_throwsValidation() {
        GrantConsentRequest req = new GrantConsentRequest(
                "v0.0.1", "bailian", List.of(), List.of(), "hash");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.grant(req, 100L));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("撤回后 current 返回 WITHDRAWN")
    void withdraw_thenCurrent_returnsWithdrawn() {
        AiConsent granted = consent(1L, 100L, ConsentStatus.GRANTED);
        when(repository.findFirstByUserIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.of(granted));
        when(repository.save(any(AiConsent.class))).thenAnswer(inv -> {
            AiConsent c = inv.getArgument(0);
            c.setId(2L);
            c.setCreatedAt(LocalDateTime.now());
            return c;
        });

        ConsentResponse response = service.withdraw(100L);

        assertEquals(ConsentStatus.WITHDRAWN, response.status());
        // 撤回事件继承原事件的元数据
        assertEquals(POLICY_VERSION, response.policyVersion());
        assertEquals("bailian", response.providerCode());
    }

    @Test
    @DisplayName("无同意记录时撤回抛 NOT_FOUND")
    void withdraw_noPriorConsent_throwsNotFound() {
        when(repository.findFirstByUserIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.withdraw(100L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("事件溯源: 撤回追加新事件,不修改历史")
    void withdraw_appendsNewEvent_preservesHistory() {
        AiConsent granted = consent(1L, 100L, ConsentStatus.GRANTED);
        when(repository.findFirstByUserIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.of(granted));
        when(repository.save(any(AiConsent.class))).thenAnswer(inv -> {
            AiConsent c = inv.getArgument(0);
            c.setId(2L);
            c.setCreatedAt(LocalDateTime.now());
            return c;
        });

        service.withdraw(100L);

        verify(repository, times(1)).save(captor.capture());
        AiConsent withdrawnEvent = captor.getValue();
        assertEquals(ConsentStatus.WITHDRAWN, withdrawnEvent.getEventType());
        // 新事件继承 GRANTED 事件的 policyVersion 和 providerCode
        assertEquals(granted.getPolicyVersion(), withdrawnEvent.getPolicyVersion());
        assertEquals(granted.getProviderCode(), withdrawnEvent.getProviderCode());
        // 原事件未被修改
        assertEquals(ConsentStatus.GRANTED, granted.getEventType());
    }

    @Test
    @DisplayName("hasValidConsent: 最新事件为 GRANTED 返回 true,WITHDRAWN 返回 false")
    void hasValidConsent_checksLatestEvent() {
        AiConsent granted = consent(1L, 100L, ConsentStatus.GRANTED);
        when(repository.findFirstByUserIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.of(granted));
        assertTrue(service.hasValidConsent(100L));

        AiConsent withdrawn = consent(2L, 100L, ConsentStatus.WITHDRAWN);
        when(repository.findFirstByUserIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.of(withdrawn));
        assertFalse(service.hasValidConsent(100L));

        when(repository.findFirstByUserIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.empty());
        assertFalse(service.hasValidConsent(100L));
    }

    @Test
    void scopedConsentRequiresCurrentPolicyScopeAndEveryCategory() {
        AiConsent granted = consent(1L, 100L, ConsentStatus.GRANTED);
        granted.setTaskScopesJson(List.of("JOB_GENERATION"));
        granted.setDataCategoriesJson(List.of("JOB_DESCRIPTION", "CAREER_MATERIAL", "PERSONAL_PROFILE"));
        when(repository.findFirstByUserIdOrderByCreatedAtDesc(100L)).thenReturn(Optional.of(granted));

        assertTrue(service.hasValidConsent(100L, "JOB_GENERATION",
                List.of("JOB_DESCRIPTION", "PERSONAL_PROFILE")));
        assertFalse(service.hasValidConsent(100L, "JOB_MATERIAL_SELECTION",
                List.of("JOB_DESCRIPTION")));
        assertFalse(service.hasValidConsent(100L, "JOB_GENERATION",
                List.of("UNKNOWN_CATEGORY")));
    }

    private AiConsent consent(Long id, Long userId, ConsentStatus status) {
        AiConsent c = new AiConsent();
        c.setId(id);
        c.setUserId(userId);
        c.setEventType(status);
        c.setPolicyVersion(POLICY_VERSION);
        c.setProviderCode("bailian");
        c.setTaskScopesJson(List.of("JOB_GENERATION"));
        c.setDataCategoriesJson(List.of("resume"));
        c.setNoticeHash("hash123");
        c.setCreatedAt(LocalDateTime.now());
        return c;
    }
}
