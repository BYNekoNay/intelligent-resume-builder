package com.intelligentresume.ai.task.service;

import com.intelligentresume.ai.consent.dto.ConsentRequest;
import com.intelligentresume.ai.consent.service.ConsentService;
import com.intelligentresume.ai.task.dto.TaskCreateRequest;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.ai.confirmation.dto.ConfirmRequest;
import com.intelligentresume.ai.confirmation.service.ConfirmationService;
import com.intelligentresume.auth.dto.RegisterRequest;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.auth.service.AuthService;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.dto.CareerMaterialCreateRequest;
import com.intelligentresume.careermaterial.service.CareerMaterialService;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.dto.JobDescriptionCreateRequest;
import com.intelligentresume.jobdescription.service.JobDescriptionService;
import com.intelligentresume.resume.dto.ResumeCreateRequest;
import com.intelligentresume.resume.service.ResumeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AiTaskServiceTest {

    @Autowired private AiTaskService aiTaskService;
    @Autowired private ConsentService consentService;
    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private ResumeService resumeService;
    @Autowired private JobDescriptionService jobDescriptionService;
    @Autowired private CareerMaterialService careerMaterialService;
    @Autowired private AiTaskRepository aiTaskRepository;
    @Autowired private ConfirmationService confirmationService;

    @Test
    void replaysSameIdempotencyKeyForTheSameRequestAndRejectsDifferentRequest() {
        Long userId = createUser();
        grantConsent(userId);
        var resume = resumeService.create(new ResumeCreateRequest("My resume", Map.of("basics", Map.of("name", "User"))), userId);
        var job = jobDescriptionService.create(new JobDescriptionCreateRequest("Backend Engineer", null, "Spring Boot and MySQL"), userId);
        TaskCreateRequest original = new TaskCreateRequest(resume.id(), job.id(), List.of(), List.of(), List.of(), Map.of());

        var created = aiTaskService.create(original, "same-key", userId);
        var replayed = aiTaskService.create(original, "same-key", userId);

        assertThat(created.confirmationStatus()).isEqualTo(AiTask.ConfirmationStatus.PENDING);
        assertThat(replayed.id()).isEqualTo(created.id());
        assertThatThrownBy(() -> aiTaskService.create(
                new TaskCreateRequest(resume.id(), job.id(), List.of(), List.of(), List.of(), Map.of("variant", "different")),
                "same-key", userId))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void rejectsForeignResumeJobAndMaterialIds() {
        Long ownerId = createUser();
        Long requesterId = createUser();
        grantConsent(requesterId);
        var ownerResume = resumeService.create(new ResumeCreateRequest("Owner resume", Map.of("basics", Map.of("name", "Owner"))), ownerId);
        var ownerJob = jobDescriptionService.create(new JobDescriptionCreateRequest("Owner job", null, "Java"), ownerId);
        var ownerMaterial = careerMaterialService.create(new CareerMaterialCreateRequest(
                MaterialType.SKILL, "Owner skill", Map.of("name", "Java"), null, UsagePreference.NORMAL), ownerId);
        var requesterResume = resumeService.create(new ResumeCreateRequest("Requester resume", Map.of("basics", Map.of("name", "Requester"))), requesterId);
        var requesterJob = jobDescriptionService.create(new JobDescriptionCreateRequest("Requester job", null, "Spring"), requesterId);

        assertNotFound(() -> aiTaskService.create(
                new TaskCreateRequest(ownerResume.id(), requesterJob.id(), List.of(), List.of(), List.of(), Map.of()), "foreign-resume", requesterId));
        assertNotFound(() -> aiTaskService.create(
                new TaskCreateRequest(requesterResume.id(), ownerJob.id(), List.of(), List.of(), List.of(), Map.of()), "foreign-job", requesterId));
        assertNotFound(() -> aiTaskService.create(
                new TaskCreateRequest(requesterResume.id(), requesterJob.id(), List.of(ownerMaterial.id()), List.of(), List.of(), Map.of()), "foreign-material", requesterId));
    }

    @Test
    void doesNotConfirmARejectedTask() {
        Long userId = createUser();
        grantConsent(userId);
        var resume = resumeService.create(new ResumeCreateRequest("My resume", Map.of("basics", Map.of("name", "User"))), userId);
        var job = jobDescriptionService.create(new JobDescriptionCreateRequest("Backend Engineer", null, "Spring Boot"), userId);
        var created = aiTaskService.create(
                new TaskCreateRequest(resume.id(), job.id(), List.of(), List.of(), List.of(), Map.of()), "rejected-task", userId);
        AiTask task = aiTaskRepository.findById(created.id()).orElseThrow();
        task.setStatus(AiTask.TaskStatus.SUCCESS);
        task.setConfirmationStatus(AiTask.ConfirmationStatus.REJECTED);
        AiTask rejected = aiTaskRepository.saveAndFlush(task);

        assertThatThrownBy(() -> confirmationService.confirm(rejected.getId(),
                new ConfirmRequest(rejected.getUpdatedAt(), List.of(), null), "confirm-rejected", userId))
                .isInstanceOf(BusinessException.class)
                .extracting(Throwable::getMessage)
                .isEqualTo("Task is not awaiting confirmation");
    }

    @Test
    void doesNotRejectATaskBeforeItSucceeds() {
        Long userId = createUser();
        grantConsent(userId);
        var resume = resumeService.create(new ResumeCreateRequest("My resume", Map.of("basics", Map.of("name", "User"))), userId);
        var job = jobDescriptionService.create(new JobDescriptionCreateRequest("Backend Engineer", null, "Spring Boot"), userId);
        var created = aiTaskService.create(
                new TaskCreateRequest(resume.id(), job.id(), List.of(), List.of(), List.of(), Map.of()), "pending-task", userId);
        AiTask task = aiTaskRepository.findById(created.id()).orElseThrow();

        assertThatThrownBy(() -> confirmationService.reject(task.getId(),
                new com.intelligentresume.ai.confirmation.dto.RejectRequest(task.getUpdatedAt()), userId))
                .isInstanceOf(BusinessException.class)
                .extracting(Throwable::getMessage)
                .isEqualTo("Task is not awaiting confirmation");
    }

    @Test
    void requiresAnExplicitDecisionForEveryPendingDraftItem() {
        Long userId = createUser();
        AiTask task = new AiTask();
        task.setUserId(userId);
        task.setTaskType(AiTask.TaskType.JOB_GENERATION);
        task.setIdempotencyKey("pending-confirmation");
        task.setRequestFingerprint("test");
        task.setInputSnapshotJson(Map.of("resumeId", 1L));
        task.setStatus(AiTask.TaskStatus.SUCCESS);
        task.setConfirmationStatus(AiTask.ConfirmationStatus.PENDING);
        task.setResultJson(Map.of("draftResumeJson", Map.of(
                "basics", Map.of("summary", Map.of("_pending", true, "value", "Generated summary")))));
        AiTask saved = aiTaskRepository.saveAndFlush(task);

        assertThatThrownBy(() -> confirmationService.confirm(saved.getId(),
                new ConfirmRequest(saved.getUpdatedAt(), List.of(
                        new ConfirmRequest.ConfirmedDraftItem("/basics", ConfirmRequest.Decision.ACCEPT, null)),
                null), "missing-pending", userId))
                .isInstanceOf(BusinessException.class)
                .extracting(Throwable::getMessage)
                .isEqualTo("All pending draft items require a decision");
    }

    private Long createUser() {
        String username = "task_" + UUID.randomUUID().toString().substring(0, 8);
        authService.register(new RegisterRequest(username, username + "@example.com", "StrongPassword!1"));
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    private void grantConsent(Long userId) {
        consentService.grant(new ConsentRequest("v1", "mock", List.of("JOB_GENERATION"), List.of("resume"), "notice"), userId);
    }

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
