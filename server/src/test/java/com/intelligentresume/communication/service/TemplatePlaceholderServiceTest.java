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

    // ===== P1-3 回归：提取容忍空白（{{ name }}），替换必须同口径 =====

    @Test
    @DisplayName("fill: 带空格占位符 {{ name }} 有值时被替换（P1-3）")
    void fill_spacedPlaceholder_withValue_isReplaced() {
        JobDescription job = new JobDescription();
        job.setTitle("后端工程师");
        job.setCompanyName("示例公司");
        Map<String, Object> resume = Map.of("basics", Map.of("name", "张明远"));

        TemplatePlaceholderService.FillResult result = service.fill(
                "您好 {{ candidateName }}，应聘 {{ jobTitle }} @ {{ companyName }}", resume, job);

        assertEquals("您好 张明远，应聘 后端工程师 @ 示例公司", result.filledBody());
        assertTrue(result.missingPlaceholders().isEmpty());
    }

    @Test
    @DisplayName("fill: 带空格占位符 {{ name }} 无值时进 missing（P1-3）")
    void fill_spacedPlaceholder_withoutValue_isMissing() {
        JobDescription job = new JobDescription();
        TemplatePlaceholderService.FillResult result = service.fill(
                "您好 {{ candidateName }}，应聘 {{ jobTitle }}", Map.of("basics", Map.of()), job);

        // 无值占位符原样保留（含原空白格式），并列入 missing
        assertEquals("您好 {{ candidateName }}，应聘 {{ jobTitle }}", result.filledBody());
        assertEquals(java.util.List.of("candidateName", "jobTitle"), result.missingPlaceholders());
    }

    @Test
    @DisplayName("fill: 无空格占位符 {{name}} 行为不变（有值替换 / 无值 missing）")
    void fill_unspacedPlaceholder_behaviorUnchanged() {
        JobDescription job = new JobDescription();
        job.setTitle("后端工程师");
        TemplatePlaceholderService.FillResult withValue = service.fill(
                "{{candidateName}} 申请 {{jobTitle}}", Map.of("basics", Map.of("name", "张明远")), job);
        assertEquals("张明远 申请 后端工程师", withValue.filledBody());
        assertTrue(withValue.missingPlaceholders().isEmpty());

        TemplatePlaceholderService.FillResult withoutValue = service.fill(
                "{{candidateName}} 申请 {{jobTitle}}", Map.of("basics", Map.of()), job);
        assertEquals("{{candidateName}} 申请 后端工程师", withoutValue.filledBody());
        assertEquals(java.util.List.of("candidateName"), withoutValue.missingPlaceholders());
    }

    @Test
    @DisplayName("fill: 值包含 $ 与 \\ 时不被正则替换语义破坏（quoteReplacement）")
    void fill_valueWithRegexSpecialChars_isLiteral() {
        JobDescription job = new JobDescription();
        Map<String, Object> resume = Map.of(
                "basics", Map.of("name", "张$三", "email", "a\\b@test.com"));

        TemplatePlaceholderService.FillResult result = service.fill(
                "{{candidateName}} <{{email}}>", resume, job);

        assertEquals("张$三 <a\\b@test.com>", result.filledBody());
    }

    @Test
    @DisplayName("fill: 同一占位符出现多次全部替换")
    void fill_repeatedPlaceholder_allReplaced() {
        JobDescription job = new JobDescription();
        Map<String, Object> resume = Map.of("basics", Map.of("name", "张明远"));

        TemplatePlaceholderService.FillResult result = service.fill(
                "{{candidateName}} 你好，{{candidateName}} 再见，{{ candidateName }} 空格版", resume, job);

        assertEquals("张明远 你好，张明远 再见，张明远 空格版", result.filledBody());
    }

    @Test
    @DisplayName("validate: 带空格的非白名单占位符仍抛 40001（行为不回归）")
    void validate_spacedIllegalPlaceholder_throwsValidation() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validate("{{ hackerInput }} 非法占位符"));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("hackerInput"));
    }
}
