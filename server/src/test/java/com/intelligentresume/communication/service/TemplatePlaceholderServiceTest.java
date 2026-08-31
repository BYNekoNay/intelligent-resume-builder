package com.intelligentresume.communication.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板占位符服务单元测试：白名单校验 + 纯字符串填充。
 */
class TemplatePlaceholderServiceTest {

    private final TemplatePlaceholderService service = new TemplatePlaceholderService();

    @Test
    @DisplayName("validate: 白名单占位符通过")
    void validate_whitelistOnly_passes() {
        service.validate("您好 {{candidateName}}，来自 {{companyName}} 的 {{jobTitle}}");
    }

    @Test
    @DisplayName("validate: 非法占位符抛 40001")
    void validate_illegalPlaceholder_throwsValidation() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validate("{{hackerInput}} 非法占位符"));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("hackerInput"));
    }

    @Test
    @DisplayName("fill: 用真实简历/JD 填充并列出缺失占位符")
    void fill_replacesKnownAndListsMissing() {
        JobDescription job = new JobDescription();
        job.setTitle("后端工程师");
        job.setCompanyName("示例公司");
        Map<String, Object> resume = Map.of(
                "basics", Map.of("name", "张明远", "location", "上海", "email", "a@b.com", "phone", "13800138000"),
                "skills", java.util.List.of(Map.of("name", "Java"), Map.of("name", "Spring")));

        TemplatePlaceholderService.FillResult result = service.fill(
                "{{candidateName}} 申请 {{jobTitle}} @ {{companyName}}，技能 {{topSkill}}，位于 {{location}}，联系 {{email}}/{{phone}}，未知 {{missingThing}}",
                resume, job);

        assertTrue(result.filledBody().contains("张明远"));
        assertTrue(result.filledBody().contains("后端工程师"));
        assertTrue(result.filledBody().contains("示例公司"));
        assertTrue(result.filledBody().contains("Java"));
        assertTrue(result.filledBody().contains("上海"));
        assertTrue(result.filledBody().contains("a@b.com"));
        assertTrue(result.filledBody().contains("13800138000"));
        // 不在白名单内的 {{missingThing}} 原样保留并列出
        assertTrue(result.filledBody().contains("{{missingThing}}"));
        assertEquals(java.util.List.of("missingThing"), result.missingPlaceholders());
    }

    @Test
    @DisplayName("fill: 缺失的白名单占位符原样保留并列入 missingPlaceholders")
    void fill_missingWhitelistValue_isReported() {
        JobDescription job = new JobDescription();
        job.setTitle("职位");
        TemplatePlaceholderService.FillResult result = service.fill(
                "{{candidateName}} 无姓名，{{location}} 无地点", Map.of("basics", Map.of()), job);

        assertTrue(result.filledBody().contains("{{candidateName}}"));
        assertTrue(result.filledBody().contains("{{location}}"));
        assertEquals(java.util.List.of("candidateName", "location"), result.missingPlaceholders());
    }
}
