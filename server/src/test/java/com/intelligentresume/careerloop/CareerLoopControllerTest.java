package com.intelligentresume.careerloop;

import com.intelligentresume.application.domain.ApplicationRecord;
import com.intelligentresume.application.dto.ApplicationCreateRequest;
import com.intelligentresume.application.dto.ApplicationStatusRequest;
import com.intelligentresume.application.service.ApplicationService;
import com.intelligentresume.ai.consent.dto.ConsentRequest;
import com.intelligentresume.ai.consent.service.ConsentService;
import com.intelligentresume.ai.material.dto.MaterialResumeGenerationRequest;
import com.intelligentresume.ai.material.service.MaterialResumeGenerationService;
import com.intelligentresume.ats.dto.AtsCheckRequest;
import com.intelligentresume.ats.service.AtsCheckService;
import com.intelligentresume.auth.dto.RegisterRequest;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.auth.service.AuthService;
import com.intelligentresume.jobdescription.dto.JobDescriptionCreateRequest;
import com.intelligentresume.jobdescription.service.JobDescriptionService;
import com.intelligentresume.interview.dto.InterviewAnswerAssetCreateRequest;
import com.intelligentresume.interview.service.InterviewAnswerAssetService;
import com.intelligentresume.interview.service.InterviewService;
import com.intelligentresume.interview.dto.InterviewStartRequest;
import com.intelligentresume.interview.dto.InterviewAnswerRequest;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.dto.ResumeCreateRequest;
import com.intelligentresume.resume.dto.ResumeVersionCreateRequest;
import com.intelligentresume.resume.service.ResumeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class CareerLoopControllerTest {
    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private ResumeService resumeService;
    @Autowired private JobDescriptionService jobService;
    @Autowired private AtsCheckService atsService;
    @Autowired private ApplicationService applicationService;
    @Autowired private InterviewAnswerAssetService interviewAssetService;
    @Autowired private ConsentService consentService;
    @Autowired private MaterialResumeGenerationService materialGenerationService;
    @Autowired private InterviewService interviewService;

    @Test
    void atsCheckSeparatesRuleHealthFromHiringProbability() {
        long userId = createUser();
        Fixture fixture = createFixture(userId);
        var response = atsService.check(new AtsCheckRequest(fixture.versionId(), fixture.jobId()), userId);

        assertThat(response.totalScore()).isBetween(java.math.BigDecimal.ZERO, java.math.BigDecimal.valueOf(100));
        assertThat(response.disclaimer()).contains("不是企业 ATS 结果").contains("录用概率");
        assertThat(response.checks()).containsKeys("keywordCoverage", "structure", "length");
        assertThat(response.passedChecks()).isNotEmpty();
        assertThat(response.priorities()).isSubsetOf(response.risks());
    }

    @Test
    void applicationStatusUpdatesOnlyOwnedRecord() {
        long owner = createUser();
        Fixture fixture = createFixture(owner);
        var application = applicationService.create(new ApplicationCreateRequest(
                fixture.jobId(), fixture.versionId(), ApplicationRecord.Status.DRAFT, "草稿", "邮件正文", "开场白"), owner);
        var updated = applicationService.updateStatus(application.id(),
                new ApplicationStatusRequest(ApplicationRecord.Status.APPLIED, application.version(), "已提交"), owner);

        assertThat(updated.status()).isEqualTo(ApplicationRecord.Status.APPLIED);
        assertThat(updated.emailBodyText()).isEqualTo("邮件正文");
        assertThat(updated.appliedAt()).isNotNull();
        long other = createUser();
        assertThatThrownBy(() -> applicationService.updateStatus(application.id(),
                new ApplicationStatusRequest(ApplicationRecord.Status.REJECTED, application.version(), ""), other))
                .hasMessageContaining("资源不存在");
    }

    @Test
    void applicationRejectsSkippedAndTerminalStatusTransitions() {
        long userId = createUser();
        Fixture fixture = createFixture(userId);
        var application = applicationService.create(new ApplicationCreateRequest(
                fixture.jobId(), fixture.versionId(), ApplicationRecord.Status.DRAFT, null, null, null), userId);

        assertThatThrownBy(() -> applicationService.updateStatus(application.id(),
                new ApplicationStatusRequest(ApplicationRecord.Status.OFFERED, application.version(), null), userId))
                .isInstanceOf(com.intelligentresume.common.error.BusinessException.class)
                .extracting(error -> ((com.intelligentresume.common.error.BusinessException) error).getErrorCode())
                .isEqualTo(com.intelligentresume.common.error.ErrorCode.CONFLICT);

        var applied = applicationService.updateStatus(application.id(), new ApplicationStatusRequest(ApplicationRecord.Status.APPLIED, application.version(), null), userId);
        applicationService.updateStatus(application.id(), new ApplicationStatusRequest(ApplicationRecord.Status.REJECTED, applied.version(), null), userId);
        assertThatThrownBy(() -> applicationService.updateStatus(application.id(),
                new ApplicationStatusRequest(ApplicationRecord.Status.INTERVIEWING, applied.version() + 1, null), userId))
                .isInstanceOf(com.intelligentresume.common.error.BusinessException.class)
                .extracting(error -> ((com.intelligentresume.common.error.BusinessException) error).getErrorCode())
                .isEqualTo(com.intelligentresume.common.error.ErrorCode.CONFLICT);
    }

    @Test
    void applicationMustStartAsDraft() {
        long userId = createUser();
        Fixture fixture = createFixture(userId);

        assertThatThrownBy(() -> applicationService.create(new ApplicationCreateRequest(
                fixture.jobId(), fixture.versionId(), ApplicationRecord.Status.OFFERED, null, null, null), userId))
                .isInstanceOf(com.intelligentresume.common.error.BusinessException.class)
                .extracting(error -> ((com.intelligentresume.common.error.BusinessException) error).getErrorCode())
                .isEqualTo(com.intelligentresume.common.error.ErrorCode.CONFLICT);
    }

    @Test
    void applicationStatusRejectsStaleVersion() {
        long userId = createUser();
        Fixture fixture = createFixture(userId);
        var application = applicationService.create(new ApplicationCreateRequest(
                fixture.jobId(), fixture.versionId(), ApplicationRecord.Status.DRAFT, null, null, null), userId);
        applicationService.updateStatus(application.id(), new ApplicationStatusRequest(
                ApplicationRecord.Status.APPLIED, application.version(), null), userId);

        assertThatThrownBy(() -> applicationService.updateStatus(application.id(), new ApplicationStatusRequest(
                ApplicationRecord.Status.APPLIED, application.version(), "stale feedback"), userId))
                .isInstanceOf(com.intelligentresume.common.error.BusinessException.class)
                .extracting(error -> ((com.intelligentresume.common.error.BusinessException) error).getErrorCode())
                .isEqualTo(com.intelligentresume.common.error.ErrorCode.CONFLICT);
    }

    @Test
    void applicationFullUpdateCannotBypassStatusTransitions() {
        long userId = createUser();
        Fixture fixture = createFixture(userId);
        var application = applicationService.create(new ApplicationCreateRequest(
                fixture.jobId(), fixture.versionId(), ApplicationRecord.Status.DRAFT, null, null, null), userId);

        assertThatThrownBy(() -> applicationService.update(application.id(), new ApplicationCreateRequest(
                fixture.jobId(), fixture.versionId(), ApplicationRecord.Status.OFFERED, null, null, null, application.version()), userId))
                .isInstanceOf(com.intelligentresume.common.error.BusinessException.class)
                .extracting(error -> ((com.intelligentresume.common.error.BusinessException) error).getErrorCode())
                .isEqualTo(com.intelligentresume.common.error.ErrorCode.CONFLICT);
    }

    @Test
    void applicationFullUpdateRejectsStaleVersion() {
        long userId = createUser();
        Fixture fixture = createFixture(userId);
        var application = applicationService.create(new ApplicationCreateRequest(
                fixture.jobId(), fixture.versionId(), ApplicationRecord.Status.DRAFT, null, null, null), userId);
        applicationService.update(application.id(), new ApplicationCreateRequest(
                fixture.jobId(), fixture.versionId(), ApplicationRecord.Status.DRAFT, "first edit", null, null, application.version()), userId);

        assertThatThrownBy(() -> applicationService.update(application.id(), new ApplicationCreateRequest(
                fixture.jobId(), fixture.versionId(), ApplicationRecord.Status.DRAFT, "stale edit", null, null, application.version()), userId))
                .isInstanceOf(com.intelligentresume.common.error.BusinessException.class)
                .extracting(error -> ((com.intelligentresume.common.error.BusinessException) error).getErrorCode())
                .isEqualTo(com.intelligentresume.common.error.ErrorCode.CONFLICT);
    }

    @Test
    void applicationCanBeDeletedOnlyByItsOwner() {
        long ownerId = createUser();
        Fixture fixture = createFixture(ownerId);
        var application = applicationService.create(new ApplicationCreateRequest(
                fixture.jobId(), fixture.versionId(), ApplicationRecord.Status.DRAFT, null, null, null), ownerId);
        long otherId = createUser();

        assertThatThrownBy(() -> applicationService.delete(application.id(), otherId))
                .isInstanceOf(com.intelligentresume.common.error.BusinessException.class)
                .extracting(error -> ((com.intelligentresume.common.error.BusinessException) error).getErrorCode())
                .isEqualTo(com.intelligentresume.common.error.ErrorCode.NOT_FOUND);

        applicationService.delete(application.id(), ownerId);
        assertThatThrownBy(() -> applicationService.get(application.id(), ownerId))
                .isInstanceOf(com.intelligentresume.common.error.BusinessException.class)
                .extracting(error -> ((com.intelligentresume.common.error.BusinessException) error).getErrorCode())
                .isEqualTo(com.intelligentresume.common.error.ErrorCode.NOT_FOUND);
    }

    @Test
    void interviewAssetKeepsOriginalAndAiSuggestionSeparate() {
        long userId = createUser();
        var asset = interviewAssetService.create(new InterviewAnswerAssetCreateRequest(
                null, "请介绍一次故障处理", "我先定位日志再回滚。", "建议补充影响范围和恢复时间。", Map.of("confirmed", false)), userId);
        assertThat(asset.originalAnswerText()).isEqualTo("我先定位日志再回滚。");
        assertThat(asset.suggestedAnswerText()).isNotEqualTo(asset.originalAnswerText());
        assertThat(interviewAssetService.list(userId, null, "恢复")).extracting("id").contains(asset.id());
    }

    @Test
    void interviewAssetCanOnlyBeDeletedByItsOwner() {
        long owner = createUser();
        var asset = interviewAssetService.create(new InterviewAnswerAssetCreateRequest(
                null, "如何处理冲突？", "我先澄清目标。", null, Map.of()), owner);
        long other = createUser();

        assertThatThrownBy(() -> interviewAssetService.delete(asset.id(), other))
                .isInstanceOf(com.intelligentresume.common.error.BusinessException.class)
                .extracting(error -> ((com.intelligentresume.common.error.BusinessException) error).getErrorCode())
                .isEqualTo(com.intelligentresume.common.error.ErrorCode.NOT_FOUND);

        interviewAssetService.delete(asset.id(), owner);
        assertThat(interviewAssetService.list(owner, null, null)).extracting("id").doesNotContain(asset.id());
    }

    @Test
    void interviewAssetCanBeUpdatedOnlyByItsOwner() {
        long owner = createUser();
        var asset = interviewAssetService.create(new InterviewAnswerAssetCreateRequest(
                null, "Original question", "Original answer", null, Map.of()), owner);

        var updated = interviewAssetService.update(asset.id(), new InterviewAnswerAssetCreateRequest(
                null, "Updated question", "Updated answer", "Suggested answer", Map.of("reviewed", true)), owner);

        assertThat(updated.questionText()).isEqualTo("Updated question");
        assertThat(updated.suggestedAnswerText()).isEqualTo("Suggested answer");
        long other = createUser();
        assertThatThrownBy(() -> interviewAssetService.update(asset.id(), new InterviewAnswerAssetCreateRequest(
                null, "Other question", "Other answer", null, Map.of()), other))
                .isInstanceOf(com.intelligentresume.common.error.BusinessException.class)
                .extracting(error -> ((com.intelligentresume.common.error.BusinessException) error).getErrorCode())
                .isEqualTo(com.intelligentresume.common.error.ErrorCode.NOT_FOUND);
    }

    @Test
    void materialGenerationPreservesRawTextAndRequiresConfirmation() {
        long userId = createUser();
        consentService.grant(new ConsentRequest("v1", "mock", java.util.List.of("MATERIAL_GENERATION"),
                java.util.List.of("raw_material_text"), "test"), userId);
        var response = materialGenerationService.generate(new MaterialResumeGenerationRequest(
                "3年 Java 开发经验，熟悉 Spring Boot 和 MySQL。", null), userId);
        assertThat(response.rawMaterialText()).contains("3年 Java");
        assertThat(response.generatedResumeJson()).containsKey("basics");
        assertThat(response.suggestions()).isNotEmpty();
        assertThat(response.requiresManualConfirmation()).isTrue();
    }

    @Test
    void externalResumeInterviewProducesAnswersAndReport() {
        long userId = createUser();
        Fixture fixture = createFixture(userId);
        consentService.grant(new ConsentRequest("v1", "mock", java.util.List.of("INTERVIEW"),
                java.util.List.of("resume_text", "answer_text"), "test"), userId);
        var started = interviewService.start(new InterviewStartRequest(InterviewSession.SourceType.EXTERNAL_RESUME,
                null, "Java 开发，负责订单系统。", fixture.jobId(), InterviewSession.Mode.TECHNICAL), userId);
        interviewService.answer(started.interviewId(), new InterviewAnswerRequest("我负责订单接口开发并处理数据库性能问题。"), userId);
        var report = interviewService.report(started.interviewId(), userId);
        assertThat(report.totalScore()).isPositive();
        assertThat(report.strengths()).isNotEmpty();
        assertThat(report.resumeSuggestions()).isNotEmpty();
    }

    @Test
    void interviewModeChangesTheGeneratedQuestion() {
        long userId = createUser();
        Fixture fixture = createFixture(userId);
        consentService.grant(new ConsentRequest("v1", "mock", java.util.List.of("INTERVIEW"),
                java.util.List.of("resume_text", "answer_text"), "test"), userId);

        var technical = interviewService.start(new InterviewStartRequest(InterviewSession.SourceType.EXTERNAL_RESUME,
                null, "Backend experience", fixture.jobId(), InterviewSession.Mode.TECHNICAL), userId);
        var behavioral = interviewService.start(new InterviewStartRequest(InterviewSession.SourceType.EXTERNAL_RESUME,
                null, "Backend experience", fixture.jobId(), InterviewSession.Mode.BEHAVIORAL), userId);

        assertThat(technical.firstQuestion()).contains("technical").isNotEqualTo(behavioral.firstQuestion());
        assertThat(behavioral.firstQuestion()).contains("collaboration");
    }

    @Test
    void completedInterviewRejectsAdditionalAnswers() {
        long userId = createUser();
        Fixture fixture = createFixture(userId);
        consentService.grant(new ConsentRequest("v1", "mock", java.util.List.of("INTERVIEW"),
                java.util.List.of("resume_text", "answer_text"), "test"), userId);
        var started = interviewService.start(new InterviewStartRequest(InterviewSession.SourceType.EXTERNAL_RESUME,
                null, "Java 开发，负责订单系统。", fixture.jobId(), InterviewSession.Mode.TECHNICAL), userId);
        interviewService.answer(started.interviewId(), new InterviewAnswerRequest("第一轮回答"), userId);
        interviewService.answer(started.interviewId(), new InterviewAnswerRequest("第二轮回答"), userId);
        interviewService.answer(started.interviewId(), new InterviewAnswerRequest("第三轮回答"), userId);

        assertThatThrownBy(() -> interviewService.answer(started.interviewId(),
                new InterviewAnswerRequest("不应被接受"), userId))
                .hasMessageContaining("面试会话已结束");
    }

    @Test
    void interviewAnswerRecordCanBeSavedAsAnOwnedAsset() {
        long ownerId = createUser();
        Fixture fixture = createFixture(ownerId);
        consentService.grant(new ConsentRequest("v1", "mock", java.util.List.of("INTERVIEW"),
                java.util.List.of("resume_text", "answer_text"), "test"), ownerId);
        var started = interviewService.start(new InterviewStartRequest(InterviewSession.SourceType.EXTERNAL_RESUME,
                null, "Backend engineering experience", fixture.jobId(), InterviewSession.Mode.TECHNICAL), ownerId);
        var answer = interviewService.answer(started.interviewId(),
                new InterviewAnswerRequest("I improved a Spring Boot API by measuring query latency."), ownerId);

        var asset = interviewAssetService.create(new InterviewAnswerAssetCreateRequest(answer.recordId(),
                answer.questionText(), "I improved a Spring Boot API by measuring query latency.",
                String.join(" ", answer.feedback().get("improvements")), Map.of("roundScore", answer.roundScore())), ownerId);
        assertThat(asset.interviewRecordId()).isEqualTo(answer.recordId());

        long otherId = createUser();
        assertThatThrownBy(() -> interviewAssetService.create(new InterviewAnswerAssetCreateRequest(answer.recordId(),
                answer.questionText(), "Copied answer", null, Map.of()), otherId))
                .isInstanceOf(com.intelligentresume.common.error.BusinessException.class)
                .extracting(error -> ((com.intelligentresume.common.error.BusinessException) error).getErrorCode())
                .isEqualTo(com.intelligentresume.common.error.ErrorCode.NOT_FOUND);
    }

    private long createUser() {
        String username = "career_" + UUID.randomUUID().toString().substring(0, 8);
        authService.register(new RegisterRequest(username, username + "@example.com", "StrongPassword!1"));
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    private Fixture createFixture(long userId) {
        long resumeId = resumeService.create(new ResumeCreateRequest("测试简历",
                Map.of("basics", Map.of("name", "测试"), "work", java.util.List.of(Map.of("description", "接口开发")),
                        "skills", java.util.List.of("Java"))), userId).id();
        long versionId = resumeService.createVersion(resumeId, new ResumeVersionCreateRequest(
                Map.of("basics", Map.of("name", "测试"), "work", java.util.List.of(Map.of("description", "接口开发")),
                        "skills", java.util.List.of("Java")), ResumeVersion.SourceType.MANUAL, ""), userId).id();
        long jobId = jobService.create(new JobDescriptionCreateRequest("Java 工程师", "示例公司",
                "需要 Java 接口开发经验，熟悉 MySQL。"), userId).id();
        return new Fixture(versionId, jobId);
    }

    private record Fixture(long versionId, long jobId) {}
}
