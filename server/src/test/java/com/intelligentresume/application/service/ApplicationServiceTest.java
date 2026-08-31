package com.intelligentresume.application.service;

import com.intelligentresume.application.domain.ApplicationRecord;
import com.intelligentresume.application.domain.ApplicationStatus;
import com.intelligentresume.application.dto.CreateApplicationRequest;
import com.intelligentresume.application.dto.UpdateApplicationRequest;
import com.intelligentresume.application.dto.UpdateApplicationStatusRequest;
import com.intelligentresume.application.repository.ApplicationRecordRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 投递记录服务单元测试。
 *
 * <p>覆盖乐观锁 version 冲突、投递状态机迁移约束、跨用户归属校验、
 * 引用合法性（JD / 简历版本归属）等核心业务规则。
 */
class ApplicationServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long FOREIGN_USER_ID = 8L;
    private static final Long RECORD_ID = 1L;
    private static final Long JOB_ID = 10L;
    private static final Long VERSION_ID = 20L;

    private ApplicationRecordRepository repository;
    private JobDescriptionRepository jobRepository;
    private ResumeVersionRepository versionRepository;
    private ApplicationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ApplicationRecordRepository.class);
        jobRepository = mock(JobDescriptionRepository.class);
        versionRepository = mock(ResumeVersionRepository.class);
        service = new ApplicationService(repository, jobRepository, versionRepository);
    }

    // ---- 帮助方法 ----

    private ApplicationRecord record(Long id, Long userId, ApplicationStatus status, Long version) {
        ApplicationRecord record = new ApplicationRecord();
        record.setId(id);
        record.setUserId(userId);
        record.setJobDescriptionId(JOB_ID);
        record.setResumeVersionId(VERSION_ID);
        record.setStatus(status);
        // version 字段由 JPA @Version 管理，无 setter，测试中用反射写入
        ReflectionTestUtils.setField(record, "version", version);
        return record;
    }

    private ResumeVersion version(Long createdBy, LocalDateTime deletedAt) {
        ResumeVersion version = new ResumeVersion();
        version.setCreatedBy(createdBy);
        version.setDeletedAt(deletedAt);
        return version;
    }

    private void stubOwnedRecord(ApplicationStatus status, Long version) {
        when(repository.findByIdAndUserId(RECORD_ID, USER_ID))
                .thenReturn(Optional.of(record(RECORD_ID, USER_ID, status, version)));
    }

    private void stubValidReferences() {
        when(jobRepository.findByIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(new JobDescription()));
        when(versionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version(USER_ID, null)));
    }

    // ---- 乐观锁 version 冲突 ----

    @Test
    @DisplayName("update: version 不匹配抛 40901 且不落库")
    void update_versionMismatch_throwsConflict() {
        stubOwnedRecord(ApplicationStatus.DRAFT, 2L);
        UpdateApplicationRequest request = new UpdateApplicationRequest(JOB_ID, VERSION_ID, null, null, null, null, 1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.update(RECORD_ID, request, USER_ID));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertEquals("投递记录已被更新，请刷新后重试", ex.getMessage());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updateStatus: version 不匹配抛 40901")
    void updateStatus_versionMismatch_throwsConflict() {
        stubOwnedRecord(ApplicationStatus.DRAFT, 5L);
        UpdateApplicationStatusRequest request = new UpdateApplicationStatusRequest(ApplicationStatus.APPLIED, 4L, null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.updateStatus(RECORD_ID, request, USER_ID));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertEquals("投递记录已被更新，请刷新后重试", ex.getMessage());
    }

    // ---- 状态机迁移 ----

    @Test
    @DisplayName("updateStatus: DRAFT 不能直接迁移到 OFFERED")
    void updateStatus_illegalTransition_throwsConflict() {
        stubOwnedRecord(ApplicationStatus.DRAFT, 1L);
        UpdateApplicationStatusRequest request = new UpdateApplicationStatusRequest(ApplicationStatus.OFFERED, 1L, null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.updateStatus(RECORD_ID, request, USER_ID));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不允许从 DRAFT 迁移到 OFFERED"));
    }

    @Test
    @DisplayName("updateStatus: 终态 REJECTED 不可再迁移")
    void updateStatus_terminalStateRejectsMigration_throwsConflict() {
        stubOwnedRecord(ApplicationStatus.REJECTED, 1L);
        UpdateApplicationStatusRequest request = new UpdateApplicationStatusRequest(ApplicationStatus.APPLIED, 1L, null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.updateStatus(RECORD_ID, request, USER_ID));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不允许从 REJECTED 迁移到 APPLIED"));
    }

    @Test
    @DisplayName("updateStatus: DRAFT→APPLIED 合法并写入 appliedAt 与 feedback")
    void updateStatus_draftToApplied_setsAppliedAtAndFeedback() {
        stubOwnedRecord(ApplicationStatus.DRAFT, 1L);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UpdateApplicationStatusRequest request = new UpdateApplicationStatusRequest(ApplicationStatus.APPLIED, 1L, "已投递，等待回复");

        service.updateStatus(RECORD_ID, request, USER_ID);

        ArgumentCaptor<ApplicationRecord> captor = ArgumentCaptor.forClass(ApplicationRecord.class);
        verify(repository).saveAndFlush(captor.capture());
        ApplicationRecord saved = captor.getValue();
        assertEquals(ApplicationStatus.APPLIED, saved.getStatus());
        assertEquals("已投递，等待回复", saved.getFeedbackText());
        assertNotNull(saved.getAppliedAt());
    }

    @Test
    @DisplayName("update: 通过 update 接口改状态被拒绝")
    void update_statusChangeViaUpdateEndpoint_throwsConflict() {
        stubOwnedRecord(ApplicationStatus.DRAFT, 1L);
        UpdateApplicationRequest request = new UpdateApplicationRequest(JOB_ID, VERSION_ID, ApplicationStatus.APPLIED, null, null, null, 1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.update(RECORD_ID, request, USER_ID));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertEquals("请通过状态接口更新投递状态", ex.getMessage());
    }

    // ---- 跨用户归属校验 ----

    @Test
    @DisplayName("update: 访问他人记录抛 40401")
    void update_foreignUserRecord_throwsNotFound() {
        when(repository.findByIdAndUserId(RECORD_ID, FOREIGN_USER_ID)).thenReturn(Optional.empty());
        UpdateApplicationRequest request = new UpdateApplicationRequest(JOB_ID, VERSION_ID, null, null, null, null, 1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.update(RECORD_ID, request, FOREIGN_USER_ID));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertEquals("投递记录不存在", ex.getMessage());
    }

    @Test
    @DisplayName("delete: 访问他人记录抛 40401 且不删除")
    void delete_foreignUserRecord_throwsNotFound() {
        when(repository.findByIdAndUserId(RECORD_ID, FOREIGN_USER_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.delete(RECORD_ID, FOREIGN_USER_ID));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        verify(repository, never()).delete(any());
    }

    // ---- 创建与引用校验 ----

    @Test
    @DisplayName("create: 非 DRAFT 初始状态被拒绝")
    void create_nonDraftStatus_throwsConflict() {
        stubValidReferences();
        CreateApplicationRequest request = new CreateApplicationRequest(JOB_ID, VERSION_ID, ApplicationStatus.APPLIED, null, null, null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(request, USER_ID));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertEquals("投递记录必须从 DRAFT 状态创建", ex.getMessage());
    }

    @Test
    @DisplayName("create: JD 不存在抛 40401")
    void create_missingJobDescription_throwsNotFound() {
        when(jobRepository.findByIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.empty());
        CreateApplicationRequest request = new CreateApplicationRequest(JOB_ID, VERSION_ID, null, null, null, null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(request, USER_ID));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertEquals("JD 不存在", ex.getMessage());
    }

    @Test
    @DisplayName("create: 简历版本属于他人抛 40401")
    void create_foreignResumeVersion_throwsNotFound() {
        when(jobRepository.findByIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(new JobDescription()));
        when(versionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version(FOREIGN_USER_ID, null)));
        CreateApplicationRequest request = new CreateApplicationRequest(JOB_ID, VERSION_ID, null, null, null, null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(request, USER_ID));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertEquals("简历版本不存在", ex.getMessage());
    }

    @Test
    @DisplayName("create: 已删除的简历版本抛 40401")
    void create_deletedResumeVersion_throwsNotFound() {
        when(jobRepository.findByIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(new JobDescription()));
        when(versionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version(USER_ID, LocalDateTime.now())));
        CreateApplicationRequest request = new CreateApplicationRequest(JOB_ID, VERSION_ID, null, null, null, null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(request, USER_ID));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("create: 引用合法时保存并返回 DRAFT 响应")
    void create_validReferences_savesAndReturnsResponse() {
        stubValidReferences();
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CreateApplicationRequest request = new CreateApplicationRequest(JOB_ID, VERSION_ID, null, "cover", null, null);

        var response = service.create(request, USER_ID);

        assertEquals(ApplicationStatus.DRAFT, response.status());
        assertEquals(JOB_ID, response.jobDescriptionId());
        assertEquals(VERSION_ID, response.resumeVersionId());
        assertEquals("cover", response.coverLetterText());
        verify(repository).saveAndFlush(any());
    }

    // ---- 列表 ----

    @Test
    @DisplayName("list: 仅返回当前用户记录，按更新时间倒序")
    void list_returnsOwnedRecords() {
        ApplicationRecord older = record(1L, USER_ID, ApplicationStatus.DRAFT, 1L);
        ApplicationRecord newer = record(2L, USER_ID, ApplicationStatus.APPLIED, 2L);
        when(repository.findByUserIdOrderByUpdatedAtDesc(USER_ID)).thenReturn(List.of(newer, older));

        var responses = service.list(USER_ID);

        assertEquals(2, responses.size());
        assertEquals(ApplicationStatus.APPLIED, responses.get(0).status());
        assertEquals(ApplicationStatus.DRAFT, responses.get(1).status());
        verify(repository).findByUserIdOrderByUpdatedAtDesc(USER_ID);
    }
}
