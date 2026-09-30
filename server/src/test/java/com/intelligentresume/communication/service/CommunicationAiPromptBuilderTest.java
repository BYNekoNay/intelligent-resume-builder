package com.intelligentresume.communication.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.communication.domain.CommunicationOutputLanguage;
import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.dto.GenerateCommunicationRequest;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.resume.domain.ResumeSections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CommunicationAiPromptBuilder 单元测试（ideation #55）。
 *
 * <p>章节白名单统一取自 {@code ResumeSections.AI_CONTEXT_SECTIONS}：此前沟通 prompt 只投影 8 章，
 * objective / 志愿 / 课程 / 成果 / 自定义模块被静默丢弃；本测试锚定扩展后的章节覆盖，
 * 同时验证 links 联系方式容器与 PII 仍被剔除。
 */
class CommunicationAiPromptBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CommunicationAiPromptBuilder builder =
            new CommunicationAiPromptBuilder(objectMapper, "communication-v1.0.0", "communication-schema-v1.0.0");

    @Test
    @DisplayName("#55：AI 上下文覆盖全章节，links 与联系方式/PII 仍被剔除")
    void taskInput_coversFullAiContextSections() throws Exception {
        Map<String, Object> input = builder.buildTaskInput(
                new GenerateCommunicationRequest(11L, 20L, CommunicationType.COVER_LETTER,
                        CommunicationOutputLanguage.ZH_CN),
                resumeJson(), job());

        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = (Map<String, Object>) input.get("resumeJson");
        assertTrue(sanitized.keySet().containsAll(List.of("objective", "volunteering", "courses",
                "publications", "awards", "customSections")),
                "本次扩展的章节应进入沟通 prompt：" + sanitized.keySet());
        assertTrue(sanitized.keySet().containsAll(
                List.of("basics", "work")), "原有章节不应被破坏：" + sanitized.keySet());
        assertFalse(sanitized.containsKey("links"), "links 是联系方式容器，不得进入 AI 输入");

        String serialized = objectMapper.writeValueAsString(input);
        for (String expected : List.of("Platform Engineer", "Code Club", "Distributed Systems",
                "Scaling Notes", "Hackathon Winner", "Open Source", "resume-builder")) {
            assertTrue(serialized.contains(expected), "提示词应包含 " + expected);
        }
        assertFalse(serialized.contains("alice@example.com"), "邮箱必须脱敏或被剔除");
        assertFalse(serialized.contains("https://example.com/alice"), "URL 必须脱敏或被剔除");
        assertFalse(serialized.contains("Shanghai"), "objective.location 属联系方式字段，必须剔除");
    }

    @Test
    @DisplayName("#55：共享章节常量排除 links，且包含本次扩展的五个章节")
    void sharedSectionConstant_contract() {
        assertFalse(ResumeSections.AI_CONTEXT_SECTIONS.contains("links"));
        assertTrue(ResumeSections.AI_CONTEXT_SECTIONS.containsAll(
                List.of("objective", "volunteering", "courses", "publications", "customSections")));
    }

    private Map<String, Object> resumeJson() {
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("basics", Map.of("name", "Alice", "email", "alice@example.com", "summary", "Backend engineer"));
        resume.put("objective", Map.of("targetRole", "Platform Engineer", "location", "Shanghai"));
        resume.put("links", List.of(Map.of("label", "GitHub", "url", "https://example.com/alice")));
        resume.put("work", List.of(Map.of("company", "ACME", "position", "Engineer")));
        resume.put("volunteering", List.of(Map.of("organization", "Code Club", "role", "Mentor")));
        resume.put("courses", List.of(Map.of("name", "Distributed Systems", "provider", "Example University")));
        resume.put("publications", List.of(Map.of("title", "Scaling Notes", "publisher", "Tech Press")));
        resume.put("awards", List.of(Map.of("name", "Hackathon Winner", "issuer", "ACME")));
        resume.put("customSections", List.of(Map.of("title", "Open Source", "entries",
                List.of(Map.of("name", "resume-builder", "role", "Maintainer")))));
        return resume;
    }

    private JobDescription job() {
        JobDescription job = new JobDescription();
        job.setId(20L);
        job.setTitle("Platform Engineer");
        job.setCompanyName("Example Systems");
        job.setJdText("Java platform reliability");
        return job;
    }
}