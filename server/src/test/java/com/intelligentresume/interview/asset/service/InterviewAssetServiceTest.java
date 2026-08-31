package com.intelligentresume.interview.asset.service;

import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.asset.domain.InterviewAnswerAsset;
import com.intelligentresume.interview.asset.dto.InterviewAssetRequest;
import com.intelligentresume.interview.asset.repository.InterviewAnswerAssetRepository;
import com.intelligentresume.interview.asset.repository.InterviewAssetSectionRepository;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 面试答案资产服务单元测试：幂等 create + 章节校验。
 */
class InterviewAssetServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long RECORD_ID = 5L;

    private InterviewAnswerAssetRepository repository;
    private InterviewAssetSectionRepository sectionRepository;
    private InterviewRecordRepository recordRepository;
    private JobDescriptionRepository jobRepository;
    private CareerMaterialRepository materialRepository;
    private InterviewAssetService service;

    @BeforeEach
    void setUp() {
        repository = mock(InterviewAnswerAssetRepository.class);
        sectionRepository = mock(InterviewAssetSectionRepository.class);
        recordRepository = mock(InterviewRecordRepository.class);
        jobRepository = mock(JobDescriptionRepository.class);
        materialRepository = mock(CareerMaterialRepository.class);
        service = new InterviewAssetService(repository, sectionRepository, recordRepository,
                jobRepository, materialRepository);
    }

    private com.intelligentresume.interview.domain.InterviewRecord record() {
        com.intelligentresume.interview.domain.InterviewRecord record =
                new com.intelligentresume.interview.domain.InterviewRecord();
        record.setId(RECORD_ID);
        return record;
    }

    private InterviewAnswerAsset asset(Long id) {
        InterviewAnswerAsset asset = new InterviewAnswerAsset();
        asset.setId(id);
        asset.setUserId(USER_ID);
        asset.setInterviewRecordId(RECORD_ID);
        asset.setQuestionText("问题");
        asset.setOriginalAnswerText("回答");
        return asset;
    }

    @Test
    @DisplayName("create: (userId, interviewRecordId) 已存在时返回已有资产，不重复创建")
    void create_idempotent_returnsExisting() {
        when(recordRepository.findOwned(RECORD_ID, USER_ID)).thenReturn(Optional.of(record()));
        when(repository.findByUserIdAndInterviewRecordId(USER_ID, RECORD_ID))
                .thenReturn(Optional.of(asset(11L)));
        when(sectionRepository.findByAssetId(11L)).thenReturn(List.of());
        InterviewAssetRequest request = new InterviewAssetRequest(RECORD_ID, "问题", "回答", null,
                Map.of(), List.of("work"), List.of());

        var result = service.create(request, USER_ID);

        assertEquals(11L, result.id());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("create: 非法章节键抛 40001 且不落库")
    void create_invalidSectionKey_throwsValidation() {
        when(recordRepository.findOwned(RECORD_ID, USER_ID)).thenReturn(Optional.of(record()));
        when(repository.findByUserIdAndInterviewRecordId(USER_ID, RECORD_ID)).thenReturn(Optional.empty());
        InterviewAssetRequest request = new InterviewAssetRequest(RECORD_ID, "问题", "回答", null,
                Map.of(), List.of("not-a-section"), List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(request, USER_ID));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("create: 合法请求创建并保存章节关联")
    void create_valid_savesAssetAndSections() {
        when(recordRepository.findOwned(RECORD_ID, USER_ID)).thenReturn(Optional.of(record()));
        when(repository.findByUserIdAndInterviewRecordId(USER_ID, RECORD_ID)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            InterviewAnswerAsset saved = invocation.getArgument(0);
            saved.setId(42L);
            return saved;
        });
        when(sectionRepository.findByAssetId(42L)).thenReturn(List.of());
        InterviewAssetRequest request = new InterviewAssetRequest(RECORD_ID, "问题", "回答", null,
                Map.of(), List.of("work"), List.of());

        var result = service.create(request, USER_ID);

        assertEquals(42L, result.id());
        verify(sectionRepository).save(any());
    }

    @Test
    @DisplayName("create: 素材不属于当前用户抛 40401")
    void create_foreignMaterial_throwsNotFound() {
        when(recordRepository.findOwned(RECORD_ID, USER_ID)).thenReturn(Optional.of(record()));
        when(repository.findByUserIdAndInterviewRecordId(USER_ID, RECORD_ID)).thenReturn(Optional.empty());
        when(materialRepository.findByIdAndUserId(99L, USER_ID)).thenReturn(Optional.empty());
        InterviewAssetRequest request = new InterviewAssetRequest(RECORD_ID, "问题", "回答", null,
                Map.of(), List.of("work"), List.of(99L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(request, USER_ID));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }
}
