package com.intelligentresume.ai.selection.service;

import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.selection.dto.ConfirmMaterialsRequest;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.ai.task.service.IdempotencyService;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.personalprofile.repository.PersonalProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaterialSelectionConfirmationServiceTest {

    private final AiTaskRepository taskRepository = mock(AiTaskRepository.class);
    private final CareerMaterialRepository materialRepository = mock(CareerMaterialRepository.class);
    private final JobDescriptionRepository jobRepository = mock(JobDescriptionRepository.class);
    private final PersonalProfileRepository profileRepository = mock(PersonalProfileRepository.class);
    private final AiConsentService consentService = mock(AiConsentService.class);
    private MaterialSelectionConfirmationService service;
    private AiTask selection;

    @BeforeEach
    void setUp() {
        service = new MaterialSelectionConfirmationService(taskRepository, materialRepository,
                jobRepository, profileRepository, new IdempotencyService(), consentService);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 26, 10, 0);
        selection = new AiTask();
        selection.setId(10L);
        selection.setUserId(7L);
        selection.setTaskType(AiTaskType.JOB_MATERIAL_SELECTION);
        selection.setStatus(AiTaskStatus.SUCCESS);
        selection.setConfirmationStatus(ConfirmationStatus.PENDING);
        selection.setUpdatedAt(updatedAt);
        selection.setInputSnapshotJson(Map.of(
                "jobDescriptionId", 8L,
                "input", Map.of("excludedMaterialIds", List.of(2L))));
        selection.setResultJson(Map.of(
                "recommended", List.of(Map.of("materialId", 1L)),
                "unselected", List.of(),
                "excluded", List.of(
                        Map.of("materialId", 2L, "exclusionReason", "MANUAL"),
                        Map.of("materialId", 3L, "exclusionReason", "GLOBAL"))));

        when(taskRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(selection));
        when(consentService.hasValidConsent(anyLong(), anyString(), anyCollection())).thenReturn(true);
        when(taskRepository.findByUserIdAndTaskTypeAndIdempotencyKey(anyLong(), any(), anyString()))
                .thenReturn(Optional.empty());
        JobDescription job = new JobDescription();
        job.setId(8L);
        job.setUserId(7L);
        job.setTitle("Java 后端工程师");
        job.setJdText("Java Spring Boot");
        when(jobRepository.findByIdAndUserId(8L, 7L)).thenReturn(Optional.of(job));
        when(profileRepository.findByUserId(7L)).thenReturn(Optional.empty());
        when(materialRepository.findByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(List.of(
                material(1L, UsagePreference.NORMAL),
                material(2L, UsagePreference.NORMAL),
                material(3L, UsagePreference.EXCLUDED),
                material(4L, UsagePreference.NORMAL)));
    }

    @Test
    void rejectsOwnedMaterialThatWasNotInSelectionCandidates() {
        ConfirmMaterialsRequest request = new ConfirmMaterialsRequest(
                selection.getUpdatedAt(), List.of(4L), List.of(), "岗位简历");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm(10L, request, "confirm-1", 7L));

        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void manualExclusionCannotBeForcedBackIntoGeneration() {
        ConfirmMaterialsRequest request = new ConfirmMaterialsRequest(
                selection.getUpdatedAt(), List.of(1L), List.of(2L), "岗位简历");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm(10L, request, "confirm-2", 7L));

        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void carriesSelectionGapsIntoTheImmutableGenerationSnapshot() {
        selection.setResultJson(Map.of(
                "recommended", List.of(Map.of("materialId", 1L)),
                "unselected", List.of(),
                "excluded", List.of(),
                "missingRequirements", List.of("Kafka production experience")));
        doAnswer(invocation -> invocation.getArgument(0)).when(taskRepository).save(any(AiTask.class));
        ConfirmMaterialsRequest request = new ConfirmMaterialsRequest(
                selection.getUpdatedAt(), List.of(1L), List.of(), "Job resume");

        AiTask generation = service.confirm(10L, request, "confirm-3", 7L);

        assertEquals(List.of("Kafka production experience"),
                generation.getInputSnapshotJson().get("missingRequirements"));
    }

    private CareerMaterial material(Long id, UsagePreference preference) {
        CareerMaterial material = new CareerMaterial();
        material.setId(id);
        material.setUserId(7L);
        material.setTitle("Material " + id);
        material.setMaterialType(MaterialType.WORK_EXPERIENCE);
        material.setUsagePreference(preference);
        material.setSourceText("source");
        material.setContentJson(Map.of("description", "source"));
        return material;
    }
}
