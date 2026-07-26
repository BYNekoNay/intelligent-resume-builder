package com.intelligentresume.ai.selection.service;

import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.selection.dto.ConfirmMaterialsRequest;
import com.intelligentresume.ai.task.domain.*;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.ai.task.service.IdempotencyService;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.personalprofile.repository.PersonalProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MaterialSelectionConfirmationService {

    private static final int MAX_FINAL_MATERIALS = 30;
    private final AiTaskRepository taskRepository;
    private final CareerMaterialRepository materialRepository;
    private final JobDescriptionRepository jobRepository;
    private final PersonalProfileRepository profileRepository;
    private final IdempotencyService idempotencyService;
    private final AiConsentService consentService;

    public MaterialSelectionConfirmationService(AiTaskRepository taskRepository,
                                                CareerMaterialRepository materialRepository,
                                                JobDescriptionRepository jobRepository,
                                                PersonalProfileRepository profileRepository,
                                                IdempotencyService idempotencyService,
                                                AiConsentService consentService) {
        this.taskRepository = taskRepository;
        this.materialRepository = materialRepository;
        this.jobRepository = jobRepository;
        this.profileRepository = profileRepository;
        this.idempotencyService = idempotencyService;
        this.consentService = consentService;
    }

    @Transactional
    public AiTask confirm(Long taskId, ConfirmMaterialsRequest request, String idempotencyKey, Long userId) {
        AiTask selection = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Selection task not found"));
        if (!selection.getUserId().equals(userId) || selection.getTaskType() != AiTaskType.JOB_MATERIAL_SELECTION) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Selection task not found");
        }
        if (!consentService.hasValidConsent(userId, AiTaskType.JOB_GENERATION.name(),
                List.of("JOB_DESCRIPTION", "CAREER_MATERIAL", "PERSONAL_PROFILE"))) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }

        LinkedHashSet<Long> requestedIds = new LinkedHashSet<>(request.selectedMaterialIds());
        LinkedHashSet<Long> forcedIds = request.forcedIncludedMaterialIds() == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(request.forcedIncludedMaterialIds());
        LinkedHashSet<Long> selectedIds = new LinkedHashSet<>(requestedIds);
        selectedIds.addAll(forcedIds);
        if (selectedIds.isEmpty() || selectedIds.size() > MAX_FINAL_MATERIALS) {
            throw new BusinessException(ErrorCode.VALIDATION, "Select between 1 and 30 materials");
        }
        String childKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? "selection-confirm:" + selection.getId() : idempotencyKey;
        Map<String, Object> confirmationInput = new LinkedHashMap<>();
        confirmationInput.put("selectionTaskId", selection.getId());
        confirmationInput.put("selectedMaterialIds", new ArrayList<>(selectedIds));
        confirmationInput.put("resumeTitle", Objects.toString(request.resumeTitle(), ""));
        String fingerprint = idempotencyService.fingerprint(confirmationInput);
        Optional<AiTask> existing = taskRepository.findByUserIdAndTaskTypeAndIdempotencyKey(
                userId, AiTaskType.JOB_GENERATION, childKey);
        if (existing.isPresent()) {
            if (existing.get().getRequestFingerprint().equals(fingerprint)) return existing.get();
            throw new BusinessException(ErrorCode.CONFLICT, "Selection was already confirmed with different materials");
        }
        if (selection.getStatus() != AiTaskStatus.SUCCESS
                || selection.getConfirmationStatus() != ConfirmationStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "Selection task cannot be confirmed");
        }
        if (!Objects.equals(selection.getUpdatedAt(), request.taskUpdatedAt())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Selection task changed; refresh before confirming");
        }

        Long jobId = longValue(selection.getInputSnapshotJson().get("jobDescriptionId"));
        JobDescription job = jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Job description not found"));
        Map<Long, CareerMaterial> owned = materialRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .collect(Collectors.toMap(CareerMaterial::getId, material -> material));
        if (!owned.keySet().containsAll(selectedIds)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Career material not found");
        }
        validateSelectionBoundary(selection, requestedIds, forcedIds);

        Map<String, Object> childSnapshot = new LinkedHashMap<>();
        childSnapshot.put("taskType", AiTaskType.JOB_GENERATION.name());
        childSnapshot.put("jobDescriptionId", jobId);
        childSnapshot.put("jobSnapshot", snapshot(job));
        childSnapshot.put("materialSnapshots", selectedIds.stream().map(id -> snapshot(owned.get(id))).toList());
        childSnapshot.put("missingRequirements", stringList(selection.getResultJson().get("missingRequirements")));
        childSnapshot.put("personalProfileSnapshot", profileRepository.findByUserId(userId)
                .map(this::snapshot).orElseGet(LinkedHashMap::new));
        String resumeTitle = request.resumeTitle();
        if (resumeTitle == null || resumeTitle.isBlank()) {
            Object originalTitle = selection.getInputSnapshotJson().get("resumeTitle");
            resumeTitle = originalTitle == null ? null : originalTitle.toString();
        }
        if (resumeTitle != null && !resumeTitle.isBlank()) childSnapshot.put("resumeTitle", resumeTitle);

        AiTask child = new AiTask();
        child.setUserId(userId);
        child.setParentTaskId(selection.getId());
        child.setTaskType(AiTaskType.JOB_GENERATION);
        child.setIdempotencyKey(childKey);
        child.setRequestFingerprint(fingerprint);
        child.setInputSnapshotJson(childSnapshot);
        child.setStatus(AiTaskStatus.PENDING);
        child.setRetryCount(0);
        child = taskRepository.save(child);
        selection.setConfirmationStatus(ConfirmationStatus.CONFIRMED);
        taskRepository.save(selection);
        return child;
    }

    private void validateSelectionBoundary(AiTask selection, Set<Long> requestedIds, Set<Long> forcedIds) {
        Map<String, Object> result = selection.getResultJson() == null ? Map.of() : selection.getResultJson();
        Set<Long> candidateIds = new LinkedHashSet<>();
        candidateIds.addAll(materialIds(result.get("recommended")));
        candidateIds.addAll(materialIds(result.get("unselected")));

        Set<Long> manuallyExcluded = new LinkedHashSet<>();
        Object rawInput = selection.getInputSnapshotJson().get("input");
        if (rawInput instanceof Map<?, ?> input) {
            manuallyExcluded.addAll(longSet(input.get("excludedMaterialIds")));
        }
        Set<Long> forceableIds = new LinkedHashSet<>();
        if (result.get("excluded") instanceof Collection<?> excluded) {
            for (Object item : excluded) {
                if (!(item instanceof Map<?, ?> value)) continue;
                Long id = longValue(value.get("materialId"));
                if (id == null || manuallyExcluded.contains(id)) continue;
                if ("GLOBAL".equals(value.get("exclusionReason"))
                        || UsagePreference.EXCLUDED.name().equals(value.get("usagePreference"))) {
                    forceableIds.add(id);
                }
            }
        }

        Set<Long> regularIds = new LinkedHashSet<>(requestedIds);
        regularIds.removeAll(forcedIds);
        if (!candidateIds.containsAll(regularIds)) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "Selected materials must come from the selection candidates");
        }
        if (!forceableIds.containsAll(forcedIds)) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "Only globally excluded materials can be forcibly included");
        }
    }

    private Set<Long> materialIds(Object raw) {
        if (!(raw instanceof Collection<?> collection)) return Set.of();
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Object item : collection) {
            if (item instanceof Map<?, ?> value) {
                Long id = longValue(value.get("materialId"));
                if (id != null) ids.add(id);
            }
        }
        return ids;
    }

    private Set<Long> longSet(Object raw) {
        if (!(raw instanceof Collection<?> collection)) return Set.of();
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Object value : collection) {
            Long id = longValue(value);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private List<String> stringList(Object raw) {
        if (!(raw instanceof Collection<?> collection)) return List.of();
        return collection.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private Map<String, Object> snapshot(JobDescription job) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", job.getId());
        value.put("title", job.getTitle());
        value.put("companyName", job.getCompanyName());
        value.put("jdText", job.getJdText());
        value.put("parsedKeywordsJson", job.getParsedKeywordsJson());
        return value;
    }

    private Map<String, Object> snapshot(CareerMaterial material) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", material.getId());
        value.put("materialType", material.getMaterialType().name());
        value.put("title", material.getTitle());
        value.put("contentJson", material.getContentJson());
        value.put("sourceText", material.getSourceText());
        value.put("usagePreference", material.getUsagePreference().name());
        return value;
    }

    private Map<String, Object> snapshot(com.intelligentresume.personalprofile.domain.PersonalProfile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("fullName", profile.getFullName());
        value.put("email", profile.getEmail());
        value.put("phone", profile.getPhone());
        value.put("location", profile.getLocation());
        value.put("website", profile.getWebsite());
        value.put("profileSummary", profile.getProfileSummary());
        value.put("targetRoleTitles", profile.getTargetRoleTitles());
        value.put("targetSeniority", profile.getTargetSeniority());
        value.put("targetIndustries", profile.getTargetIndustries());
        value.put("targetWorkPreferences", profile.getTargetWorkPreferences());
        value.put("careerPositioningSummary", profile.getCareerPositioningSummary());
        return value;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.valueOf(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }
}
