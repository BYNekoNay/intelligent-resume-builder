package com.intelligentresume;

import com.intelligentresume.auth.dto.RegisterRequest;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.auth.service.AuthService;
import com.intelligentresume.jobdescription.dto.JobDescriptionCreateRequest;
import com.intelligentresume.jobdescription.service.JobDescriptionService;
import com.intelligentresume.resume.dto.ResumeCreateRequest;
import com.intelligentresume.resume.dto.ResumeVersionCreateRequest;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.service.ResumeService;
import com.intelligentresume.scoring.dto.MatchRequest;
import com.intelligentresume.scoring.service.ScoringService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PerformanceSmokeIT {
    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private ResumeService resumeService;
    @Autowired private JobDescriptionService jobDescriptionService;
    @Autowired private ScoringService scoringService;

    @Test
    void warmCrudAndScoringStayWithinDocumentedP95Targets() {
        String username = "perf_" + UUID.randomUUID().toString().substring(0, 8);
        authService.register(new RegisterRequest(username, username + "@example.com", "StrongPassword!1"));
        long userId = userRepository.findByUsername(username).orElseThrow().getId();
        var resume = resumeService.create(new ResumeCreateRequest("性能简历", Map.of("basics", Map.of("name", "测试"))), userId);
        var version = resumeService.createVersion(resume.id(), new ResumeVersionCreateRequest(
                Map.of("basics", Map.of("name", "测试"), "work", List.of(Map.of("description", "Java API")),
                        "skills", List.of(Map.of("name", "Java"))), ResumeVersion.SourceType.MANUAL, null), userId);
        var jd = jobDescriptionService.create(new JobDescriptionCreateRequest("Java 工程师", "性能测试",
                "需要 Java API 经验。"), userId);

        List<Long> crudSamples = new ArrayList<>();
        List<Long> scoringSamples = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            crudSamples.add(elapsedMicros(() -> resumeService.list(userId)));
            scoringSamples.add(elapsedMicros(() -> scoringService.score(new MatchRequest(version.id(), jd.id()), userId)));
        }
        assertThat(percentile(crudSamples, 95)).isLessThan(300_000L);
        assertThat(percentile(scoringSamples, 95)).isLessThan(2_000_000L);
    }

    private long elapsedMicros(Runnable operation) {
        long start = System.nanoTime();
        operation.run();
        return (System.nanoTime() - start) / 1_000;
    }

    private long percentile(List<Long> samples, int percentile) {
        List<Long> sorted = samples.stream().sorted().toList();
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * percentile / 100.0) - 1);
        return sorted.get(index);
    }
}
