package com.intelligentresume.resume.service;

import com.intelligentresume.ats.domain.AtsCheckResult;
import com.intelligentresume.ats.repository.AtsCheckResultRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeSourceType;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.dto.ResumeVersionDetail;
import com.intelligentresume.resume.dto.ResumeVersionSummary;
import com.intelligentresume.resume.dto.RestoreResumeVersionRequest;
import com.intelligentresume.resume.dto.SaveVersionRequest;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ResumeVersionService 单元测试（Mockito）。
 */
@ExtendWith(MockitoExtension.class)
class ResumeVersionServiceTest {

    @Mock private ResumeVersionRepository versionRepository;
    @Mock private ResumeRepository resumeRepository;
    @Mock private JsonResumeValidator jsonResumeValidator;
    @Mock private AtsCheckResultRepository atsCheckResultRepository;

    private ResumeVersionService versionService;

    @BeforeEach
    void setUp() {
        versionService = new ResumeVersionService(versionRepository, resumeRepository, jsonResumeValidator,
                atsCheckResultRepository);
    }

    @Test
    @DisplayName("正常路径: 保存第一个版本 versionNo=1")
    void save_firstVersion_versionNoIsOne() {
        Resume resume = resume(1L, 100L);
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(resume));
        when(versionRepository.findMaxVersionNoByResumeId(1L)).thenReturn(null);
        when(versionRepository.save(any(ResumeVersion.class))).thenAnswer(inv -> {
            ResumeVersion v = inv.getArgument(0);
            v.setId(10L);
            return v;
        });

        SaveVersionRequest req = new SaveVersionRequest(
                Map.of("basics", Map.of("name", "Alice")), ResumeSourceType.MANUAL, null);
        ResumeVersionDetail detail = versionService.save(1L, req, 100L);

        assertEquals(1, detail.versionNo());
        assertEquals(ResumeSourceType.MANUAL, detail.sourceType());
        // #33：详情携带 resumeId，前端可单请求完成「版本 → 所属简历」定位
        assertEquals(1L, detail.resumeId());
        // 第一个版本自动设为当前版本
        assertEquals(10L, resume.getCurrentVersionId());
    }

    @Test
    @DisplayName("正常路径: 连续保存版本号递增")
    void save_consecutiveVersionNoIncrements() {
        Resume resume = resume(1L, 100L);
        resume.setCurrentVersionId(10L); // 已有当前版本
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(resume));
        when(versionRepository.findMaxVersionNoByResumeId(1L)).thenReturn(3);
        when(versionRepository.save(any(ResumeVersion.class))).thenAnswer(inv -> {
            ResumeVersion v = inv.getArgument(0);
            v.setId(11L);
            return v;
        });

        SaveVersionRequest req = new SaveVersionRequest(
                Map.of("basics", Map.of("name", "Bob")), ResumeSourceType.MANUAL, "第二次修改");
        ResumeVersionDetail detail = versionService.save(1L, req, 100L);

        assertEquals(4, detail.versionNo());
        // 已有当前版本，不自动切换
        assertEquals(10L, resume.getCurrentVersionId());
    }

    @Test
    @DisplayName("失败路径: 并发保存同 resume 触发唯一约束,后提交者得到 40901")
    void save_concurrentUniqueConstraint_conflict() {
        Resume resume = resume(1L, 100L);
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(resume));
        when(versionRepository.findMaxVersionNoByResumeId(1L)).thenReturn(1);
        when(versionRepository.save(any(ResumeVersion.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry"));

        SaveVersionRequest req = new SaveVersionRequest(
                Map.of("basics", Map.of("name", "Alice")), ResumeSourceType.MANUAL, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> versionService.save(1L, req, 100L));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    @Test
    @DisplayName("失败路径: 历史版本不可修改(本卡不提供 update 接口)")
    void historicalVersion_immutableByContract() {
        // 契约断言：ResumeVersionService 不包含 update 方法。
        // 本测试通过反射验证该类没有名为 "update" 的公开方法。
        boolean hasUpdateMethod = false;
        for (var method : ResumeVersionService.class.getMethods()) {
            if (method.getName().equals("update")
                    && method.getDeclaringClass() == ResumeVersionService.class) {
                hasUpdateMethod = true;
                break;
            }
        }
        assertFalse(hasUpdateMethod, "ResumeVersionService 不应提供 update 方法（历史版本不可修改）");
    }

    @Test
    @DisplayName("正常路径: 列出版本历史按版本号降序（摘要投影 + 历史行 template_code 惰性回填）")
    void listByResume_descending() {
        Resume resume = resume(1L, 100L);
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(resume));

        LocalDateTime now = LocalDateTime.now();
        // V32 之前的历史行：模板列为 NULL，读路径惰性回填（派生自 resume_json）
        ResumeVersion legacy = version(10L, 1L, 1);
        legacy.setResumeJson(Map.of("basics", Map.of("name", "Test")));
        when(versionRepository.findActiveSummariesByResumeId(1L)).thenReturn(List.of(
                new SummaryRow(11L, 2, ResumeSourceType.MANUAL, "modern", null, now, null, null),
                new SummaryRow(10L, 1, ResumeSourceType.MANUAL, null, null, now, null, null)));
        when(versionRepository.findAllById(List.of(10L))).thenReturn(List.of(legacy));

        List<ResumeVersionSummary> list = versionService.listByResume(1L, false, 100L);

        assertEquals(2, list.size());
        assertEquals(2, list.get(0).versionNo());
        assertEquals("modern", list.get(0).templateCode());
        assertEquals(1, list.get(1).versionNo());
        assertEquals("classic", list.get(1).templateCode());
        // 惰性回填写回实体：下次列表无需再读 resume_json
        assertEquals("classic", legacy.getTemplateCode());
    }

    @Test
    @DisplayName("#50：保存版本时即写入 template_code 派生列（归一化取白名单）")
    void save_storesDerivedTemplateCode() {
        Resume resume = resume(1L, 100L);
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(resume));
        when(versionRepository.findMaxVersionNoByResumeId(1L)).thenReturn(null);
        when(versionRepository.save(any(ResumeVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        SaveVersionRequest req = new SaveVersionRequest(
                Map.of("template", Map.of("code", "modern")), ResumeSourceType.MANUAL, null);
        versionService.save(1L, req, 100L);

        ArgumentCaptor<ResumeVersion> captor = ArgumentCaptor.forClass(ResumeVersion.class);
        verify(versionRepository).save(captor.capture());
        assertEquals("modern", captor.getValue().getTemplateCode());
    }

    @Test
    @DisplayName("恢复历史版本会复制内容、保留来源并切换为当前版本")
    void restore_createsNewCurrentVersionWithProvenance() {
        Resume resume = resume(1L, 100L);
        resume.setCurrentVersionId(12L);
        ResumeVersion source = version(10L, 1L, 3);
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(resume));
        when(versionRepository.findByIdAndResumeId(10L, 1L)).thenReturn(Optional.of(source));
        when(versionRepository.findMaxVersionNoByResumeId(1L)).thenReturn(7);
        when(versionRepository.save(any(ResumeVersion.class))).thenAnswer(invocation -> {
            ResumeVersion saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(20L);
            return saved;
        });

        // 3 参 restore 重载已删除（仅测试使用的死代码）；无 ATS 溯源时 request 传 null
        ResumeVersionDetail restored = versionService.restore(1L, 10L, null, 100L);

        assertEquals(8, restored.versionNo());
        assertEquals(ResumeSourceType.RESTORED, restored.sourceType());
        assertEquals(source.getResumeJson(), restored.resumeJson());
        assertEquals(10L, restored.restoredFromVersionId());
        assertEquals("恢复自 v3", restored.optimizationSummary(), "恢复摘要文案（中英文已统一）");
        assertEquals(20L, resume.getCurrentVersionId());
        verify(resumeRepository).save(resume);
    }

    @Test
    @DisplayName("ATS 分析创建继任版本时仅持久化服务端校验的溯源")
    void restore_withAtsProvenance_persistsValidatedContext() {
        Resume resume = resume(1L, 100L);
        resume.setCurrentVersionId(12L);
        ResumeVersion source = version(10L, 1L, 3);
        AtsCheckResult atsResult = atsResult(31L, 100L, 10L, "COMPLETED", Map.of(
                "evidenceFindings", List.of(Map.of(
                        "section", "work",
                        "suggestion", "Add measurable impact")),
                "prioritizedActions", List.of()));
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(resume));
        when(versionRepository.findByIdAndResumeId(10L, 1L)).thenReturn(Optional.of(source));
        when(atsCheckResultRepository.findByIdAndUserId(31L, 100L)).thenReturn(Optional.of(atsResult));
        when(versionRepository.findMaxVersionNoByResumeId(1L)).thenReturn(3);
        when(versionRepository.save(any(ResumeVersion.class))).thenAnswer(invocation -> {
            ResumeVersion saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(20L);
            return saved;
        });

        ResumeVersionDetail restored = versionService.restore(1L, 10L,
                new RestoreResumeVersionRequest(31L, "evidence:0"), 100L);

        Map<String, Object> provenance = castMap(restored.generationContext().get("atsProvenance"));
        assertEquals(31L, provenance.get("resultId"));
        assertEquals(10L, provenance.get("sourceVersionId"));
        assertEquals(44L, provenance.get("jobDescriptionId"));
        assertEquals("work", provenance.get("mappedSection"));
        assertEquals("evidence", provenance.get("itemKind"));
        assertEquals(0, provenance.get("itemIndex"));
        assertEquals("Add measurable impact", provenance.get("optimizationObjective"));
        assertEquals(20L, resume.getCurrentVersionId());
    }

    @Test
    @DisplayName("无效、待分析或非本人 ATS 溯源不得切换当前版本")
    void restore_withInvalidAtsProvenance_doesNotChangeCurrentVersion() {
        Resume resume = resume(1L, 100L);
        resume.setCurrentVersionId(12L);
        ResumeVersion source = version(10L, 1L, 3);
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(resume));
        when(versionRepository.findByIdAndResumeId(10L, 1L)).thenReturn(Optional.of(source));

        AtsCheckResult pending = atsResult(31L, 100L, 10L, "ANALYZING", Map.of());
        when(atsCheckResultRepository.findByIdAndUserId(31L, 100L)).thenReturn(Optional.of(pending));
        assertThrows(BusinessException.class, () -> versionService.restore(1L, 10L,
                new RestoreResumeVersionRequest(31L, "evidence:0"), 100L));

        when(atsCheckResultRepository.findByIdAndUserId(32L, 100L)).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> versionService.restore(1L, 10L,
                new RestoreResumeVersionRequest(32L, "evidence:0"), 100L));

        AtsCheckResult completed = atsResult(33L, 100L, 10L, "COMPLETED", Map.of(
                "evidenceFindings", List.of(Map.of("section", "unknown", "suggestion", "ignored")),
                "prioritizedActions", List.of()));
        when(atsCheckResultRepository.findByIdAndUserId(33L, 100L)).thenReturn(Optional.of(completed));
        assertThrows(BusinessException.class, () -> versionService.restore(1L, 10L,
                new RestoreResumeVersionRequest(33L, "evidence:0"), 100L));

        assertEquals(12L, resume.getCurrentVersionId());
        verify(versionRepository, never()).save(any(ResumeVersion.class));
        verify(resumeRepository, never()).save(resume);
    }

    @Test
    @DisplayName("ATS 溯源拒绝源版本错配、越界和伪造条目")
    void restore_withMismatchedOrMalformedAtsItem_doesNotCreateVersion() {
        Resume resume = resume(1L, 100L);
        resume.setCurrentVersionId(12L);
        ResumeVersion source = version(10L, 1L, 3);
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(resume));
        when(versionRepository.findByIdAndResumeId(10L, 1L)).thenReturn(Optional.of(source));

        AtsCheckResult mismatched = atsResult(31L, 100L, 99L, "COMPLETED", Map.of());
        when(atsCheckResultRepository.findByIdAndUserId(31L, 100L)).thenReturn(Optional.of(mismatched));
        assertThrows(BusinessException.class, () -> versionService.restore(1L, 10L,
                new RestoreResumeVersionRequest(31L, "evidence:0"), 100L));

        AtsCheckResult completed = atsResult(32L, 100L, 10L, "COMPLETED", Map.of(
                "evidenceFindings", List.of(Map.of("section", "work", "suggestion", "Add impact")),
                "prioritizedActions", List.of()));
        when(atsCheckResultRepository.findByIdAndUserId(32L, 100L)).thenReturn(Optional.of(completed));
        assertThrows(BusinessException.class, () -> versionService.restore(1L, 10L,
                new RestoreResumeVersionRequest(32L, "evidence:1"), 100L));
        assertThrows(BusinessException.class, () -> versionService.restore(1L, 10L,
                new RestoreResumeVersionRequest(32L, "free-text:0"), 100L));

        assertEquals(12L, resume.getCurrentVersionId());
        verify(versionRepository, never()).save(any(ResumeVersion.class));
        verify(resumeRepository, never()).save(resume);
    }

    @Test
    @DisplayName("ATS prioritized action 的优化目标也由服务端结果派生")
    void restore_withAtsAction_persistsActionObjective() {
        Resume resume = resume(1L, 100L);
        ResumeVersion source = version(10L, 1L, 3);
        AtsCheckResult atsResult = atsResult(31L, 100L, 10L, "COMPLETED", Map.of(
                "evidenceFindings", List.of(),
                "prioritizedActions", List.of(Map.of(
                        "section", "skills",
                        "action", "Name the required tooling"))));
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(resume));
        when(versionRepository.findByIdAndResumeId(10L, 1L)).thenReturn(Optional.of(source));
        when(atsCheckResultRepository.findByIdAndUserId(31L, 100L)).thenReturn(Optional.of(atsResult));
        when(versionRepository.findMaxVersionNoByResumeId(1L)).thenReturn(3);
        when(versionRepository.save(any(ResumeVersion.class))).thenAnswer(invocation -> {
            ResumeVersion saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(20L);
            return saved;
        });

        ResumeVersionDetail restored = versionService.restore(1L, 10L,
                new RestoreResumeVersionRequest(31L, "action:0"), 100L);

        Map<String, Object> provenance = castMap(restored.generationContext().get("atsProvenance"));
        assertEquals("action", provenance.get("itemKind"));
        assertEquals("skills", provenance.get("mappedSection"));
        assertEquals("Name the required tooling", provenance.get("optimizationObjective"));
    }

    @Test
    @DisplayName("归档版本列表只返回已归档记录")
    void listByResume_archived_returnsOnlyArchivedVersions() {
        Resume resume = resume(1L, 100L);
        LocalDateTime archivedAt = LocalDateTime.now();
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(resume));
        when(versionRepository.findArchivedSummariesByResumeId(1L)).thenReturn(List.of(
                new SummaryRow(10L, 2, ResumeSourceType.RESTORED, "minimal", "恢复自 v1",
                        archivedAt, archivedAt, 9L)));

        List<ResumeVersionSummary> list = versionService.listByResume(1L, true, 100L);

        assertEquals(1, list.size());
        assertNotNull(list.get(0).archivedAt());
        assertEquals("minimal", list.get(0).templateCode());
        assertEquals(9L, list.get(0).restoredFromVersionId());
    }

    @Test
    @DisplayName("当前版本不能归档")
    void archive_currentVersion_rejected() {
        Resume resume = resume(1L, 100L);
        resume.setCurrentVersionId(10L);
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(resume));
        when(versionRepository.findByIdAndResumeId(10L, 1L)).thenReturn(Optional.of(version(10L, 1L, 2)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> versionService.archive(1L, 10L, 100L));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    @Test
    @DisplayName("跨简历版本不能作为恢复来源或归档目标")
    void versionFromOtherResume_cannotBeRestoredOrArchived() {
        Resume resume = resume(1L, 100L);
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(resume));
        when(versionRepository.findByIdAndResumeId(10L, 1L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> versionService.restore(1L, 10L, null, 100L));
        assertThrows(BusinessException.class, () -> versionService.archive(1L, 10L, 100L));
    }

    private Resume resume(Long id, Long userId) {
        Resume r = new Resume();
        r.setId(id);
        r.setUserId(userId);
        r.setTitle("测试简历");
        return r;
    }

    private ResumeVersion version(Long id, Long resumeId, int versionNo) {
        ResumeVersion v = new ResumeVersion();
        v.setId(id);
        v.setResumeId(resumeId);
        v.setVersionNo(versionNo);
        v.setSourceType(ResumeSourceType.MANUAL);
        v.setResumeJson(Map.of("basics", Map.of("name", "Test")));
        v.setCreatedBy(100L);
        return v;
    }

    private AtsCheckResult atsResult(Long id, Long userId, Long sourceVersionId, String status,
                                     Map<String, Object> aiInsights) {
        AtsCheckResult result = new AtsCheckResult();
        result.setId(id);
        result.setUserId(userId);
        result.setResumeVersionId(sourceVersionId);
        result.setJobDescriptionId(44L);
        result.setResultJson(Map.of("analysisStatus", status, "aiInsights", aiInsights));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    /** 测试用摘要投影行（生产环境由 Spring Data 接口投影生成）。 */
    private record SummaryRow(Long id, Integer versionNo, ResumeSourceType sourceType,
                              String templateCode, String optimizationSummary,
                              LocalDateTime createdAt, LocalDateTime deletedAt,
                              Long restoredFromVersionId)
            implements ResumeVersionRepository.VersionSummaryProjection {
        @Override public Long getId() { return id; }
        @Override public Integer getVersionNo() { return versionNo; }
        @Override public ResumeSourceType getSourceType() { return sourceType; }
        @Override public String getTemplateCode() { return templateCode; }
        @Override public String getOptimizationSummary() { return optimizationSummary; }
        @Override public LocalDateTime getCreatedAt() { return createdAt; }
        @Override public LocalDateTime getDeletedAt() { return deletedAt; }
        @Override public Long getRestoredFromVersionId() { return restoredFromVersionId; }
    }
}
