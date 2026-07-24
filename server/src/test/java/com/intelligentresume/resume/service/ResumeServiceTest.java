package com.intelligentresume.resume.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.dto.*;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ResumeService 单元测试（Mockito）。
 */
@ExtendWith(MockitoExtension.class)
class ResumeServiceTest {

    @Mock private ResumeRepository resumeRepository;
    @Mock private ResumeVersionRepository versionRepository;
    @Mock private JsonResumeValidator jsonResumeValidator;

    private ResumeService resumeService;

    @BeforeEach
    void setUp() {
        resumeService = new ResumeService(resumeRepository, versionRepository, jsonResumeValidator);
    }

    @Test
    @DisplayName("正常路径: 创建简历并返回 Detail")
    void create_returnsDetail() {
        when(resumeRepository.save(any(Resume.class))).thenAnswer(inv -> {
            Resume r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });

        ResumeDetail detail = resumeService.create(
                new CreateResumeRequest("Java后端简历", null), 100L);

        assertEquals(1L, detail.id());
        assertEquals("Java后端简历", detail.title());
        assertNull(detail.currentVersionId());
        verify(resumeRepository).save(any(Resume.class));
    }

    @Test
    @DisplayName("正常路径: 列出当前用户的全部简历")
    void list_returnsOnlyOwn() {
        Resume r1 = resume(1L, 100L, "简历A");
        Resume r2 = resume(2L, 100L, "简历B");
        when(resumeRepository.findByUserIdOrderByUpdatedAtDesc(100L))
                .thenReturn(List.of(r2, r1));

        List<ResumeSummary> list = resumeService.list(100L);

        assertEquals(2, list.size());
        assertEquals("简历B", list.get(0).title());
    }

    @Test
    @DisplayName("失败路径: 查询他人简历返回 NOT_FOUND")
    void get_othersResume_returnsNotFound() {
        when(resumeRepository.findByIdAndUserId(1L, 999L))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> resumeService.get(1L, 999L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("正常路径: 软删后再 get 返回 NOT_FOUND")
    void softDelete_thenGet_notFound() {
        Resume r = resume(1L, 100L, "待删除");
        when(resumeRepository.findByIdAndUserId(1L, 100L))
                .thenReturn(Optional.of(r))
                .thenReturn(Optional.empty()); // 软删后 @SQLRestriction 过滤

        resumeService.softDelete(1L, 100L);
        assertNotNull(r.getDeletedAt());

        assertThrows(BusinessException.class, () -> resumeService.get(1L, 100L));
    }

    @Test
    @DisplayName("正常路径: 切换当前版本成功")
    void setCurrentVersion_success() {
        Resume r = resume(1L, 100L, "简历");
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(r));

        ResumeVersion v = new ResumeVersion();
        v.setId(10L);
        v.setResumeId(1L);
        when(versionRepository.findById(10L)).thenReturn(Optional.of(v));

        resumeService.setCurrentVersion(1L, 10L, 100L);

        assertEquals(10L, r.getCurrentVersionId());
        verify(resumeRepository).save(r);
    }

    @Test
    @DisplayName("失败路径: 切换到不属于该简历的版本返回 40901")
    void setCurrentVersion_versionBelongsToOtherResume_conflict() {
        Resume r = resume(1L, 100L, "简历");
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(r));

        ResumeVersion v = new ResumeVersion();
        v.setId(10L);
        v.setResumeId(2L); // 属于另一个简历
        when(versionRepository.findById(10L)).thenReturn(Optional.of(v));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> resumeService.setCurrentVersion(1L, 10L, 100L));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    @Test
    @DisplayName("失败路径: 归档版本不能设为当前版本")
    void setCurrentVersion_archivedVersion_conflict() {
        Resume r = resume(1L, 100L, "简历");
        when(resumeRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(r));
        ResumeVersion v = new ResumeVersion();
        v.setId(10L);
        v.setResumeId(1L);
        v.setDeletedAt(LocalDateTime.now());
        when(versionRepository.findById(10L)).thenReturn(Optional.of(v));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> resumeService.setCurrentVersion(1L, 10L, 100L));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    private Resume resume(Long id, Long userId, String title) {
        Resume r = new Resume();
        r.setId(id);
        r.setUserId(userId);
        r.setTitle(title);
        return r;
    }
}
