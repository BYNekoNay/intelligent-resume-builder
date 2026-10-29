package com.intelligentresume.export.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.export.domain.ExportStatus;
import com.intelligentresume.export.domain.ExportTask;
import com.intelligentresume.export.repository.ExportTaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 导出任务「延迟重排」的落库与领取条件（B5-2，V39）。
 *
 * <p>为什么必须走真库：{@code next_attempt_at} 的语义**全部**体现在
 * {@link ExportTaskRepository#claimableTasks} 的原生 SQL 里
 * （{@code AND (next_attempt_at IS NULL OR next_attempt_at <= NOW())}）。单元测试把仓库
 * mock 掉之后，把这段条件整行删掉也照样绿 —— 那正是本项目反复踩过的「门禁永远通过」陷阱。
 *
 * <p>写库由 {@code @Transactional} 回滚，不污染共享 H2；断言一律用「集合里有没有这一行」
 * 而不是「总数等于几」，避免与其它测试类互相干扰（{@code AiTaskQuotaAggregationIT} 曾因此误红）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExportTaskRequeueIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ExportTaskRepository exportTaskRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ExportTaskLeaseService leaseService;

    @Test
    @Transactional
    @DisplayName("next_attempt_at 未到期的任务不可领取；到期的立刻可领取（V39 条件真的生效）")
    void taskIsNotClaimableBeforeNextAttemptAt() throws Exception {
        Seeded seeded = seed();

        ExportTask waiting = saveTask(seeded.userId(), seeded.versionId(), LocalDateTime.now().plusMinutes(5));
        ExportTask due = saveTask(seeded.userId(), seeded.versionId(), LocalDateTime.now().minusSeconds(1));

        List<ExportTask> claimable = exportTaskRepository.claimableTasks(500);

        assertFalse(claimable.stream().anyMatch(t -> t.getId().equals(waiting.getId())),
                "未到 next_attempt_at 的任务不得被领取，否则重排等于无视下游给的等待时长");
        assertTrue(claimable.stream().anyMatch(t -> t.getId().equals(due.getId())),
                "已到期的任务必须可领取（否则任务永远卡在 PENDING）");
    }

    @Test
    @Transactional
    @DisplayName("releaseForRetry：回到 PENDING + 清租约 + next_attempt_at 落在未来，且此刻领不到")
    void releaseForRetryDefersTheNextAttempt() throws Exception {
        Seeded seeded = seed();

        ExportTask task = saveTask(seeded.userId(), seeded.versionId(), null);
        assertEquals(1, exportTaskRepository.acquireLease(task.getId(), "it-worker",
                LocalDateTime.now().plusSeconds(90)), "前置：应能领到该任务");
        task.setStatus(ExportStatus.RUNNING);
        task.setLeaseOwner("it-worker");
        task.setRetryCount(1);

        boolean released = leaseService.releaseForRetry(task, "PDF 服务繁忙，请稍后重试", 30);

        assertTrue(released);
        ExportTask stored = exportTaskRepository.findById(task.getId()).orElseThrow();
        assertEquals(ExportStatus.PENDING, stored.getStatus());
        assertNull(stored.getLeaseOwner(), "重排必须释放租约，否则任务要到租约过期才能被再次领取");
        assertNotNull(stored.getNextAttemptAt(), "必须记录下次可尝试时刻");
        assertTrue(stored.getNextAttemptAt().isAfter(LocalDateTime.now().plusSeconds(20)),
                "延迟应基本等于 Retry-After（30s），实际：" + stored.getNextAttemptAt());
        assertFalse(exportTaskRepository.claimableTasks(500).stream()
                        .anyMatch(t -> t.getId().equals(task.getId())),
                "重排后的任务在到期前不得被再次领取");
    }

    // ------------------------------------------------------------------ 夹具

    private record Seeded(Long userId, Long versionId) {}

    private ExportTask saveTask(Long userId, Long versionId, LocalDateTime nextAttemptAt) {
        ExportTask task = new ExportTask();
        task.setUserId(userId);
        task.setResumeVersionId(versionId);
        task.setTemplateCode("classic");
        task.setStatus(ExportStatus.PENDING);
        task.setRetryCount(1);
        task.setNextAttemptAt(nextAttemptAt);
        return exportTaskRepository.saveAndFlush(task);
    }

    /** 用真实接口建简历 + 版本，避免手搓 ResumeVersion 的必填字段。 */
    private Seeded seed() throws Exception {
        String username = "requeue_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                + "@example.com\",\"password\":\"correcthorse\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(registered.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();
        Long userId = userRepository.findByUsername(username).orElseThrow().getId();

        MvcResult resume = mockMvc.perform(post("/api/resumes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"重排IT\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Long resumeId = objectMapper.readTree(resume.getResponse().getContentAsString())
                .get("data").get("id").asLong();
        MvcResult version = mockMvc.perform(post("/api/resumes/" + resumeId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"MANUAL\",\"resumeJson\":{\"basics\":{\"name\":\"重排测试\"}}}"))
                .andExpect(status().isCreated())
                .andReturn();
        Long versionId = objectMapper.readTree(version.getResponse().getContentAsString())
                .get("data").get("id").asLong();
        return new Seeded(userId, versionId);
    }
}
