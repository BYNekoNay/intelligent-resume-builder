package com.intelligentresume.resume.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeSourceType;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.dto.ResumeVersionDetail;
import com.intelligentresume.resume.dto.ResumeVersionSummary;
import com.intelligentresume.resume.dto.SaveVersionRequest;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private ResumeVersionService versionService;

    @BeforeEach
    void setUp() {
        versionService = new ResumeVersionService(versionRepository, resumeRepository, jsonResumeValidator);
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
    @DisplayName("正常路径: 列出版本历史按版本号降序")
    void listByResume_descending() {
        Resume resume = resume(1L, 100L);
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(resume));

        ResumeVersion v2 = version(11L, 1L, 2);
        v2.setResumeJson(Map.of("basics", Map.of("name", "Test"), "template", Map.of("code", "modern")));
        ResumeVersion v1 = version(10L, 1L, 1);
        when(versionRepository.findByResumeIdAndDeletedAtIsNullOrderByVersionNoDesc(1L))
                .thenReturn(List.of(v2, v1));

        List<ResumeVersionSummary> list = versionService.listByResume(1L, false, 100L);
        assertEquals(2, list.size());
        assertEquals(2, list.get(0).versionNo());
        assertEquals("modern", list.get(0).templateCode());
        assertEquals(1, list.get(1).versionNo());
        assertEquals("classic", list.get(1).templateCode());
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

        ResumeVersionDetail restored = versionService.restore(1L, 10L, 100L);

        assertEquals(8, restored.versionNo());
        assertEquals(ResumeSourceType.RESTORED, restored.sourceType());
        assertEquals(source.getResumeJson(), restored.resumeJson());
        assertEquals(10L, restored.restoredFromVersionId());
        assertEquals(20L, resume.getCurrentVersionId());
        verify(resumeRepository).save(resume);
    }

    @Test
    @DisplayName("归档版本列表只返回已归档记录")
    void listByResume_archived_returnsOnlyArchivedVersions() {
        Resume resume = resume(1L, 100L);
        ResumeVersion archived = version(10L, 1L, 2);
        archived.setDeletedAt(LocalDateTime.now());
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(resume));
        when(versionRepository.findByResumeIdAndDeletedAtIsNotNullOrderByVersionNoDesc(1L))
                .thenReturn(List.of(archived));

        List<ResumeVersionSummary> list = versionService.listByResume(1L, true, 100L);

        assertEquals(1, list.size());
        assertNotNull(list.get(0).archivedAt());
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

        assertThrows(BusinessException.class, () -> versionService.restore(1L, 10L, 100L));
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
}
