package com.intelligentresume.scoring.service;

import com.intelligentresume.auth.dto.RegisterRequest;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.auth.service.AuthService;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.dto.JobDescriptionCreateRequest;
import com.intelligentresume.jobdescription.service.JobDescriptionService;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.dto.ResumeCreateRequest;
import com.intelligentresume.resume.dto.ResumeVersionCreateRequest;
import com.intelligentresume.resume.service.ResumeService;
import com.intelligentresume.scoring.dto.MatchRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ScoringServiceTest {

    @Autowired private ScoringService scoringService;
    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private ResumeService resumeService;
    @Autowired private JobDescriptionService jobDescriptionService;

    @Test
    void hidesOtherUsersVersionsJobsAndResults() {
        long ownerId = createUser("owner");
        long otherUserId = createUser("other");
        var resume = resumeService.create(new ResumeCreateRequest(
                "Owner resume", Map.of("basics", Map.of("name", "Owner"))), ownerId);
        var version = resumeService.createVersion(resume.id(), new ResumeVersionCreateRequest(
                Map.of("basics", Map.of("name", "Owner"), "skills", List.of(Map.of("name", "Java"))),
                ResumeVersion.SourceType.MANUAL, null), ownerId);
        var job = jobDescriptionService.create(new JobDescriptionCreateRequest(
                "Java engineer", "Acme", "Java Spring Boot"), ownerId);
        var result = scoringService.score(new MatchRequest(version.id(), job.id()), ownerId);

        assertNotFound(() -> scoringService.score(new MatchRequest(version.id(), job.id()), otherUserId));
        assertNotFound(() -> scoringService.get(result.matchResultId(), otherUserId));
    }

    private long createUser(String prefix) {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        authService.register(new RegisterRequest(username, username + "@example.com", "StrongPassword!1"));
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
