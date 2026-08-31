package com.intelligentresume.communication.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.communication.domain.CommunicationOutputLanguage;
import com.intelligentresume.communication.domain.CommunicationTemplate;
import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.domain.TemplateScene;
import com.intelligentresume.communication.dto.UpdateTemplateRequest;
import com.intelligentresume.communication.repository.CommunicationTemplateRepository;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 沟通模板服务单元测试：可见性/归属/内置模板只读/预览填充。
 */
class CommunicationTemplateServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long TEMPLATE_ID = 1L;
    private static final Long VERSION_ID = 20L;
    private static final Long JOB_ID = 30L;

    private CommunicationTemplateRepository templateRepository;
    private ResumeVersionRepository versionRepository;
    private JobDescriptionRepository jobRepository;
    private TemplatePlaceholderService placeholderService;
    private CommunicationTemplateService service;

    @BeforeEach
    void setUp() {
        templateRepository = mock(CommunicationTemplateRepository.class);
        versionRepository = mock(ResumeVersionRepository.class);
        jobRepository = mock(JobDescriptionRepository.class);
        placeholderService = new TemplatePlaceholderService();
        service = new CommunicationTemplateService(templateRepository, versionRepository, jobRepository, placeholderService);
    }

    private CommunicationTemplate template(Long userId, boolean isSystem, String bodyText) {
        CommunicationTemplate template = new CommunicationTemplate();
        template.setId(TEMPLATE_ID);
        template.setUserId(userId);
        template.setScene(TemplateScene.FOLLOW_UP);
        template.setTemplateType(CommunicationType.EMAIL);
        template.setOutputLanguage(CommunicationOutputLanguage.ZH_CN);
        template.setName("模板");
        template.setDescription(null);
        template.setBodyText(bodyText);
        template.setSystem(isSystem);
        template.setUsageCount(0);
        return template;
    }

    @Test
    @DisplayName("list: 内置 + 本人自定义均可见")
    void list_returnsBuiltinAndOwned() {
        when(templateRepository.search(eq(USER_ID), any(), any(), any()))
                .thenReturn(List.of(template(null, true, "内置"), template(USER_ID, false, "自定义")));

        var result = service.list(USER_ID, null, null, null);

        assertEquals(2, result.size());
        assertTrue(result.get(0).isSystem());
        assertTrue(!result.get(1).isSystem());
    }

    @Test
    @DisplayName("preview: 用真实简历/JD 填充占位符")
    void preview_fillsFromRealResumeAndJob() {
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template(null, true,
                "{{candidateName}} 申请 {{jobTitle}}，联系方式 {{email}}")));
        ResumeVersion version = new ResumeVersion();
        version.setResumeJson(Map.of("basics", Map.of("name", "张明远", "email", "a@b.com")));
        when(versionRepository.findByIdAndCreatedByAndDeletedAtIsNull(VERSION_ID, USER_ID))
                .thenReturn(Optional.of(version));
        JobDescription job = new JobDescription();
        job.setTitle("后端工程师");
        when(jobRepository.findByIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(job));

        var result = service.preview(TEMPLATE_ID, VERSION_ID, JOB_ID, USER_ID);

        assertTrue(result.filledBody().contains("张明远"));
        assertTrue(result.filledBody().contains("后端工程师"));
        assertTrue(result.filledBody().contains("a@b.com"));
    }

    @Test
    @DisplayName("update: 内置模板只读抛 40301")
    void update_builtinTemplate_throwsForbidden() {
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template(null, true, "内置")));
        UpdateTemplateRequest request = new UpdateTemplateRequest("改名", TemplateScene.FOLLOW_UP,
                CommunicationType.EMAIL, "{{candidateName}}", null, CommunicationOutputLanguage.ZH_CN);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(TEMPLATE_ID, request, USER_ID));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    @DisplayName("update: 他人自定义模板抛 40401")
    void update_foreignCustomTemplate_throwsNotFound() {
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template(999L, false, "他人")));
        UpdateTemplateRequest request = new UpdateTemplateRequest("改名", TemplateScene.FOLLOW_UP,
                CommunicationType.EMAIL, "{{candidateName}}", null, CommunicationOutputLanguage.ZH_CN);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(TEMPLATE_ID, request, USER_ID));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("update: 本人自定义模板可更新")
    void update_ownedCustomTemplate_updates() {
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template(USER_ID, false, "旧内容")));
        when(templateRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UpdateTemplateRequest request = new UpdateTemplateRequest("新名字", TemplateScene.THANK_YOU,
                CommunicationType.EMAIL, "感谢 {{companyName}} 的 {{jobTitle}}", "描述",
                CommunicationOutputLanguage.ZH_CN);

        var result = service.update(TEMPLATE_ID, request, USER_ID);

        assertEquals("新名字", result.name());
        assertEquals(TemplateScene.THANK_YOU, result.scene());
    }

    @Test
    @DisplayName("saveCustom: 非法占位符抛 40001 且不落库")
    void saveCustom_illegalPlaceholder_throwsValidation() {
        com.intelligentresume.communication.dto.SaveTemplateRequest request =
                new com.intelligentresume.communication.dto.SaveTemplateRequest(
                        "模板", TemplateScene.GENERAL, CommunicationType.EMAIL, "{{evil}} 内容",
                        null, CommunicationOutputLanguage.ZH_CN);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.saveCustom(request, USER_ID));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        verify(templateRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }
}
