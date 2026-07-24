package com.intelligentresume.jobdescription.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.dto.*;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * JobDescriptionService 单元测试（Mockito）。
 */
@ExtendWith(MockitoExtension.class)
class JobDescriptionServiceTest {

    @Mock private JobDescriptionRepository repository;
    @Mock private JdKeywordParser parser;

    private JobDescriptionService service;

    @BeforeEach
    void setUp() {
        service = new JobDescriptionService(repository, parser, 5000);
    }

    @Test
    @DisplayName("正常路径: 创建 JD")
    void create_success() {
        when(repository.save(any(JobDescription.class))).thenAnswer(inv -> {
            JobDescription jd = inv.getArgument(0);
            jd.setId(1L);
            return jd;
        });

        CreateJobDescriptionRequest req = new CreateJobDescriptionRequest(
                "Java后端工程师", "某科技公司", "负责 Spring Boot 微服务开发");

        JobDescriptionDetail detail = service.create(req, 100L);

        assertEquals(1L, detail.id());
        assertEquals("Java后端工程师", detail.title());
        assertEquals("某科技公司", detail.companyName());
        assertNull(detail.parsedKeywordsJson(), "新建时不应有解析结果");
    }

    @Test
    @DisplayName("正常路径: 解析覆盖 parsed_keywords_json,不改 jd_text")
    void parse_overwritesKeywordsJson_keepsJdText() {
        JobDescription jd = jd(1L, 100L, "原始 JD 文本");
        when(repository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(jd));
        when(parser.parse("原始 JD 文本")).thenReturn(
                new ParsedKeywordsResponse("Java后端", List.of("Java", "MySQL"), List.of("3年以上经验")));

        String originalText = jd.getJdText();
        JobDescriptionDetail detail = service.parse(1L, 100L);

        assertEquals(originalText, jd.getJdText(), "jd_text 不应被修改");
        assertNotNull(jd.getParsedKeywordsJson(), "parsed_keywords_json 应被写入");
        assertEquals("v1.0.0", jd.getParsedVersion());
        assertNotNull(jd.getParsedAt());
        // 验证包装结构: {"version": "v1.0.0", "data": {...}}
        assertEquals("v1.0.0", jd.getParsedKeywordsJson().get("version"));
        assertNotNull(jd.getParsedKeywordsJson().get("data"));
    }

    @Test
    @DisplayName("失败路径: 跨用户 parse 返回 NOT_FOUND")
    void parse_crossUser_notFound() {
        when(repository.findByIdAndUserId(1L, 999L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.parse(1L, 999L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("失败路径: 软删后 parse 返回 NOT_FOUND")
    void parse_afterSoftDelete_notFound() {
        JobDescription jd = jd(1L, 100L, "文本");
        when(repository.findByIdAndUserId(1L, 100L))
                .thenReturn(Optional.of(jd))
                .thenReturn(Optional.empty());

        service.softDelete(1L, 100L);
        assertNotNull(jd.getDeletedAt());

        assertThrows(BusinessException.class, () -> service.parse(1L, 100L));
    }

    @Test
    @DisplayName("失败路径: jdText 超过 max-length 返回 VALIDATION")
    void create_oversizedText_validationFails() {
        String longText = "x".repeat(5001);
        CreateJobDescriptionRequest req = new CreateJobDescriptionRequest("标题", null, longText);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req, 100L));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("失败路径: 软删后 history 快照仍可解析(只读)")
    void historySnapshot_readableAfterSoftDelete() {
        // 契约断言:softDelete 仅设置 deleted_at,不物理删除。
        // 被 match_result/resume_version.generation_context_json 引用的 JD,
        // 软删后历史快照保留 jd_text 原文(快照是独立 JSON 副本)。
        JobDescription jd = jd(1L, 100L, "JD 原文");
        when(repository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(jd));

        service.softDelete(1L, 100L);

        assertNotNull(jd.getDeletedAt(), "软删应设置 deletedAt");
        assertEquals("JD 原文", jd.getJdText(), "jd_text 原文应保留");
        verify(repository).save(jd);
        verify(repository, never()).delete(any());
    }

    private JobDescription jd(Long id, Long userId, String jdText) {
        JobDescription jd = new JobDescription();
        jd.setId(id);
        jd.setUserId(userId);
        jd.setTitle("测试 JD");
        jd.setJdText(jdText);
        return jd;
    }
}
