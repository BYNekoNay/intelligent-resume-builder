package com.intelligentresume.application.service;

import com.intelligentresume.application.domain.ApplicationRecord;
import com.intelligentresume.application.domain.ApplicationStatus;
import com.intelligentresume.application.dto.*;
import com.intelligentresume.application.repository.ApplicationRecordRepository;
import com.intelligentresume.application.repository.ApplicationStatusHistoryRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 投递记录与 9 态状态机。
 *
 * <p><b>① 解决什么业务问题</b>：用户在这个平台投了简历后，需要一张"跟踪卡"记住
 * 每次投递——投的哪家岗位、用的哪个简历版本、现在走到哪一步（待回复/筛选中/面试中/…）、
 * 下次什么时候跟进。本类就是这张跟踪卡的增删改查 + 状态推进逻辑。
 *
 * <p><b>② 在链路哪里</b>：上游是 {@code ApplicationController}（HTTP 请求先到它，再转到这里）；
 * 本类依赖四个仓储——{@link ApplicationRecordRepository}（投递主表）、
 * {@link ApplicationStatusHistoryRepository}（状态历史）、{@link JobDescriptionRepository}
 * （校验岗位存在）、{@link ResumeVersionRepository}（校验简历版本存在，来自 resume 模块）；
 * 产出 DTO 给前端看板页面。统计算法不在这里，委托给 {@link ApplicationStatsCalculator} 纯函数。
 *
 * <p><b>③ 改坏它用户会看到什么</b>：状态白名单漏一条 → 前端看板拖拽报 409；
 * 忘写状态历史 → 审计时间线（详情页的迁移记录）缺行；误刷 {@code stageEnteredAt}
 * → 看板"平均停留天数"偏短（历史上真实发生过的 P1-4 缺陷）。
 *
 * <p>三条不变量（新手改代码前先读懂这三条，违者都会造成线上可见的错误）：
 * <ol>
 *   <li><b>迁移是白名单</b>：只有 {@code TRANSITIONS} 里的边可通行，其余一律 {@code CONFLICT}。新增状态必须
 *       同步改这里、前端状态数组与 {@code docs/04} 表格（门禁逐格比对），否则出现「后端能迁、前端画不出」。</li>
 *   <li><b>状态只能走 {@link #updateStatus}</b>：{@link #update} 发现 status 与当前值不同即拒绝，
 *       否则「编辑备注」会绕过状态历史改状态。</li>
 *   <li><b>{@code stageEnteredAt} 只在真正迁移时刷新</b>：它是「各阶段平均停留天数」的唯一依据；历史缺陷
 *       P1-4 把它错当 {@code updatedAt}，任意编辑都会重置停留时长，看板转化周期因此系统性偏短。</li>
 * </ol>
 * <p>每个状态都允许自环（「仍是此状态，只补备注」）。禁止自环会逼前端先跳走再跳回，在审计时间线上
 * **伪造一次从未发生的迁移** —— 比多一条无变化的记录危险得多。
 * <p>并发与删除：写操作基于 {@code version} 乐观锁（版本号字段，防并发覆盖：客户端保存时回传
 * 自己读到的版本号，与库里不一致说明有人先改过，拒绝保存而不是覆盖）；删除自 V40 起为软删
 * （不真删，打 deleted_at 标记，查询时自动过滤），保留期后由 {@code RetentionPurgeService} 物理删除，
 * 状态历史随父行级联消亡。
 * <p>统计口径不在本类：{@code ApplicationStatsCalculator} 是纯函数。
 */
@Service
public class ApplicationService {

    /**
     * 允许的状态迁移（白名单）。每行第一项是源状态，集合内是可去的目标状态。
     *
     * <p>三个终态（{@code HIRED} / {@code REJECTED} / {@code WITHDRAWN}）**只能自环**，
     * 进入后不再回流 —— 需要回到流程中时应当新建一条投递记录，而不是篡改历史。
     * {@code NO_RESPONSE} 是唯一「可被重新打开」的准终态：雇主后来回复时，用户要能一步走到
     * {@code SCREENING}/{@code INTERVIEWING}；若强制经过 {@code APPLIED}，就会在时间线上
     * 伪造一次 {@code NO_RESPONSE → APPLIED} 的事件。
     */
    private static final Map<ApplicationStatus, Set<ApplicationStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(ApplicationStatus.DRAFT, Set.of(ApplicationStatus.DRAFT, ApplicationStatus.APPLIED, ApplicationStatus.WITHDRAWN)),
            Map.entry(ApplicationStatus.APPLIED, Set.of(ApplicationStatus.APPLIED, ApplicationStatus.SCREENING, ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFERED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN, ApplicationStatus.NO_RESPONSE)),
            Map.entry(ApplicationStatus.SCREENING, Set.of(ApplicationStatus.SCREENING, ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFERED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN, ApplicationStatus.NO_RESPONSE)),
            Map.entry(ApplicationStatus.INTERVIEWING, Set.of(ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFERED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN, ApplicationStatus.NO_RESPONSE)),
            Map.entry(ApplicationStatus.OFFERED, Set.of(ApplicationStatus.OFFERED, ApplicationStatus.HIRED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN, ApplicationStatus.NO_RESPONSE)),
            Map.entry(ApplicationStatus.HIRED, Set.of(ApplicationStatus.HIRED)),
            Map.entry(ApplicationStatus.REJECTED, Set.of(ApplicationStatus.REJECTED)),
            Map.entry(ApplicationStatus.WITHDRAWN, Set.of(ApplicationStatus.WITHDRAWN)),
            // A no-response outcome can be reopened when the employer responds, closed as
            // withdrawn by the user, or confirmed as a rejection. The most common real-world
            // ending is "no reply, eventually confirmed rejected", so REJECTED must be reachable
            // in one step -- routing it through APPLIED would forge a NO_RESPONSE->APPLIED event
            // in the audit timeline that never happened. REJECTED is terminal: it only self-loops.
            Map.entry(ApplicationStatus.NO_RESPONSE, Set.of(ApplicationStatus.NO_RESPONSE, ApplicationStatus.APPLIED, ApplicationStatus.SCREENING, ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFERED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN)));

    private static final String FOLLOW_UP_ALL = "ALL";
    private static final String FOLLOW_UP_TODAY = "TODAY";
    private static final String FOLLOW_UP_OVERDUE = "OVERDUE";

    private final ApplicationRecordRepository repository;
    private final JobDescriptionRepository jobRepository;
    private final ResumeVersionRepository versionRepository;
    private final ApplicationStatusHistoryRepository historyRepository;

    /** Retained for focused legacy unit tests; Spring uses the history-aware constructor. */
    public ApplicationService(ApplicationRecordRepository repository,
                              JobDescriptionRepository jobRepository,
                              ResumeVersionRepository versionRepository) {
        this(repository, jobRepository, versionRepository, null);
    }

    @Autowired
    public ApplicationService(ApplicationRecordRepository repository,
                              JobDescriptionRepository jobRepository,
                              ResumeVersionRepository versionRepository,
                              ApplicationStatusHistoryRepository historyRepository) {
        this.repository = repository;
        this.jobRepository = jobRepository;
        this.versionRepository = versionRepository;
        this.historyRepository = historyRepository;
    }

    /**
     * 列表页：按跟进筛选条件返回摘要卡片。
     * ① 把 followUp 参数归一成 ALL/TODAY/OVERDUE（非法值静默降级为 ALL，不报错——筛选条件传错不该 400）；
     * ② 算出"今天"的起止边界与当前时刻，交给仓储的跟进过滤查询；
     * ③ 每行转成摘要 DTO（不含长文本，详情接口再按需拉取）。
     */
    @Transactional(readOnly = true)
    public List<ApplicationSummary> list(Long userId, String followUp) {
        String mode = normalizeFollowUp(followUp);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1);
        return repository.findByUserIdAndFollowUp(userId, mode, startOfDay, endOfDay, now)
                .stream().map(this::summary).toList();
    }

    /**
     * 详情页：一条投递的完整数据（含长文本 + 状态历史时间线）。列表摘要不含历史，
     * 前端展开卡片时才来这里拉。
     */
    @Transactional(readOnly = true)
    public ApplicationResponse get(Long id, Long userId) {
        ApplicationRecord record = owned(id, userId);
        return response(record, readHistory(record.getId()));
    }

    /**
     * 创建一条投递记录（用户对着某个 JD 点"我要投这份简历"）。
     * ① 校验岗位与简历版本都存在且属于当前用户（防止拿别人的 JD/版本拼一条假投递）；
     * ② 强制初始状态为 DRAFT——投递必须从草稿开始，"已投出去"要走状态迁移接口，
     *    这样 appliedAt 和状态历史才会被正确记录；
     * ③ 创建即写 stageEnteredAt（P1-4 修复）：DRAFT 阶段的停留时长从这一刻起算；
     * ④ 保存并写第一条状态历史（from 为 null，因为它是这条投递时间线的起点）。
     * 步骤②必须在③④之前：顺序换了会出现"没校验归属就落库"或"历史表指向不存在的记录"。
     */
    @Transactional
    public ApplicationResponse create(CreateApplicationRequest request, Long userId) {
        validateReferences(request.jobDescriptionId(), request.resumeVersionId(), userId);
        ApplicationStatus initialStatus = request.status() == null ? ApplicationStatus.DRAFT : request.status();
        if (initialStatus != ApplicationStatus.DRAFT) {
            throw new BusinessException(ErrorCode.CONFLICT, "投递记录必须从 DRAFT 状态创建");
        }
        ApplicationRecord record = new ApplicationRecord();
        record.setUserId(userId);
        record.setJobDescriptionId(request.jobDescriptionId());
        record.setResumeVersionId(request.resumeVersionId());
        record.setStatus(initialStatus);
        // 修复 P1-4：创建即进入 DRAFT，记录初始状态进入时刻
        LocalDateTime now = LocalDateTime.now();
        record.setStageEnteredAt(now);
        record.setCoverLetterText(request.coverLetterText());
        record.setEmailBodyText(request.emailBodyText());
        record.setOpeningMessageText(request.openingMessageText());
        record.setNextFollowUpAt(request.nextFollowUpAt());
        ApplicationRecord saved = repository.saveAndFlush(record);
        recordHistory(saved.getId(), null, initialStatus, null, now);
        return response(saved, readHistory(saved.getId()));
    }

    /**
     * 编辑投递记录的文案类字段（求职信/邮件正文/开场白/跟进时间），<b>不含状态</b>。
     * ① 确认记录存在且属于当前用户，并校验乐观锁版本号（不一致 = 别人先改过，拒绝）；
     * ② 请求里若带了与当前值不同的 status，直接拒绝——状态只能走 {@link #updateStatus}，
     *    否则这次编辑不写状态历史，审计时间线会失真；
     * ③ 若想改绑 JD/简历版本：只有还在 DRAFT 且从未真正投出（appliedAt 为空）才允许，
     *    已进入流程的记录改绑会让历史与统计口径对不上；
     * ④ 应用字段并保存。注意这里<b>绝不刷新 stageEnteredAt</b>：status 没变化，
     *    刷了就会重置停留时长（P1-4 缺陷的反面教训）。
     */
    @Transactional
    public ApplicationResponse update(Long id, UpdateApplicationRequest request, Long userId) {
        ApplicationRecord record = owned(id, userId);
        requireVersion(record, request.version());
        if (request.status() != null && request.status() != record.getStatus()) {
            throw new BusinessException(ErrorCode.CONFLICT, "请通过状态接口更新投递状态");
        }
        boolean lineageChanged = !Objects.equals(request.jobDescriptionId(), record.getJobDescriptionId())
                || !Objects.equals(request.resumeVersionId(), record.getResumeVersionId());
        if (lineageChanged && (record.getAppliedAt() != null || record.getStatus() != ApplicationStatus.DRAFT)) {
            throw new BusinessException(ErrorCode.CONFLICT, "已进入投递流程的岗位和简历版本不可更改");
        }
        // 修复 P1-4：本方法不会改变 status（与当前值不一致时已在上方抛出），
        // 因此严禁刷新 stageEnteredAt —— 正是原缺陷中 updatedAt 被任意编辑重置的反面。
        validateReferences(request.jobDescriptionId(), request.resumeVersionId(), userId);
        record.setJobDescriptionId(request.jobDescriptionId());
        record.setResumeVersionId(request.resumeVersionId());
        record.setCoverLetterText(request.coverLetterText());
        record.setEmailBodyText(request.emailBodyText());
        record.setOpeningMessageText(request.openingMessageText());
        record.setNextFollowUpAt(request.nextFollowUpAt());
        ApplicationRecord saved = repository.saveAndFlush(record);
        return response(saved, readHistory(saved.getId()));
    }

    /**
     * 状态迁移（全系统<b>唯一</b>合法的状态变更入口，前端看板拖拽卡片最终调到这里）。
     * ① 确认记录归属并校验乐观锁版本号；
     * ② 查 {@link #TRANSITIONS} 白名单：源状态 → 目标状态这条边不存在就 409（比如 HIRED 想回 SCREENING）；
     * ③ 首次进入 APPLIED 时写入 appliedAt（只写一次，之后不动——它是"投了多久"的起点）；
     * ④ 状态真变了才刷新 stageEnteredAt（自环如 DRAFT→DRAFT 不刷，否则停留时长被重置，P1-4 教训）；
     * ⑤ feedbackText 是 PATCH 语义：null = 保持原备注不动（拖拽改状态的调用不该顺手清掉备注），
     *    空白串 = 显式清空；
     * ⑥ 只有真实迁移才写一条状态历史，自环不写（避免时间线噪声）。
     */
    @Transactional
    public ApplicationResponse updateStatus(Long id, UpdateApplicationStatusRequest request, Long userId) {
        ApplicationRecord record = owned(id, userId);
        requireVersion(record, request.version());
        if (!TRANSITIONS.get(record.getStatus()).contains(request.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "不允许从 " + record.getStatus() + " 迁移到 " + request.status());
        }
        boolean statusChanged = request.status() != record.getStatus();
        LocalDateTime changedAt = LocalDateTime.now();
        ApplicationStatus previousStatus = record.getStatus();
        if (request.status() == ApplicationStatus.APPLIED && record.getAppliedAt() == null) {
            record.setAppliedAt(changedAt);
        }
        if (statusChanged) {
            // 修复 P1-4：仅当状态实际发生迁移时刷新进入时刻；
            // 状态未变化（如 DRAFT→DRAFT、补 feedback）不得刷新，否则停留时长被重置。
            record.setStageEnteredAt(changedAt);
        }
        record.setStatus(request.status());
        // PATCH 语义：feedbackText 缺席或 null = 不改动备注（否则「拖拽改状态」这类不关心备注的
        // 调用会把已有备注清空）；显式空串（或纯空白）= 清空备注。
        if (request.feedbackText() != null) {
            record.setFeedbackText(request.feedbackText().isBlank() ? null : request.feedbackText());
        }
        ApplicationRecord saved = repository.saveAndFlush(record);
        if (statusChanged) {
            recordHistory(saved.getId(), previousStatus, request.status(), request.note(), changedAt);
        }
        return response(saved, readHistory(saved.getId()));
    }

    /**
     * 软删除（V40 起）：置 {@code deleted_at}，行在恢复期（30 天）+ 宽限期（7 天）后由
     * {@code RetentionPurgeService} 物理删除。此前是硬删，与 docs/04 §7.1 的承诺不一致
     * （用户误删不可逆）。状态历史是 ON DELETE CASCADE 的从属内容，物理删除时随行消亡。
     */
    @Transactional
    public void delete(Long id, Long userId) {
        ApplicationRecord record = owned(id, userId);
        record.setDeletedAt(LocalDateTime.now());
        repository.save(record);
    }

    /**
     * 看板顶部的转化漏斗统计（总数、各状态占比、转化率、各阶段平均停留）。
     * 本方法只负责两次仓储调用与事务边界；统计口径与算法见 ApplicationStatsCalculator
     * （纯函数，可单测钉住，也避免本类继续变胖）。
     * #3：计数在 SQL 聚合（group by），只有需要逐行时间戳的三个阶段才读行；
     * 时长聚合在 Java 侧计算，兼容 MySQL 与 H2（不依赖 TIMESTAMPDIFF）。
     */
    @Transactional(readOnly = true)
    public ApplicationStatsResponse stats(Long userId) {
        return ApplicationStatsCalculator.calculate(
                repository.countGroupByStatus(userId),
                repository.findDurationRowsByUserId(userId),
                LocalDateTime.now());
    }

    private String normalizeFollowUp(String followUp) {
        if (followUp == null || followUp.isBlank()) return FOLLOW_UP_ALL;
        String upper = followUp.trim().toUpperCase();
        if (FOLLOW_UP_TODAY.equals(upper) || FOLLOW_UP_OVERDUE.equals(upper)) return upper;
        return FOLLOW_UP_ALL;
    }

    private void validateReferences(Long jobId, Long versionId, Long userId) {
        jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "JD 不存在"));
        ResumeVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在"));
        // 归属校验先于归档判定：不通过时一律 404，避免用归档状态反推他人版本是否存在
        if (!userId.equals(version.getCreatedBy())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在");
        }
        // 归档是可逆状态：与 ATS/评分/导出/沟通/面试一致用 409 + 可操作文案
        if (version.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.VERSION_ARCHIVED, "该简历版本已归档，请先恢复后再发起投递");
        }
    }

    private ApplicationRecord owned(Long id, Long userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "投递记录不存在"));
    }

    private void requireVersion(ApplicationRecord record, Long expected) {
        if (!expected.equals(record.getVersion())) {
            throw new BusinessException(ErrorCode.CONFLICT, "投递记录已被更新，请刷新后重试");
        }
    }

    private ApplicationResponse response(ApplicationRecord record, List<ApplicationStatusHistoryResponse> history) {
        return new ApplicationResponse(record.getId(), record.getJobDescriptionId(), record.getResumeVersionId(),
                record.getStatus(), record.getCoverLetterText(), record.getEmailBodyText(), record.getOpeningMessageText(),
                record.getFeedbackText(), record.getAppliedAt(), record.getNextFollowUpAt(), record.getVersion(),
                record.getCreatedAt(), record.getUpdatedAt(), history);
    }

    private List<ApplicationStatusHistoryResponse> readHistory(Long applicationId) {
        if (historyRepository == null) return List.of();
        return historyRepository.findByApplicationRecordIdOrderByChangedAtAscIdAsc(applicationId).stream()
                .map(item -> new ApplicationStatusHistoryResponse(item.getId(), item.getFromStatus(), item.getToStatus(),
                        item.getNote(), item.getChangedAt()))
                .toList();
    }

    private void recordHistory(Long applicationId, ApplicationStatus from, ApplicationStatus to,
                               String note, LocalDateTime changedAt) {
        if (historyRepository == null) return;
        com.intelligentresume.application.domain.ApplicationStatusHistory history =
                new com.intelligentresume.application.domain.ApplicationStatusHistory();
        history.setApplicationRecordId(applicationId);
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setNote(note == null || note.isBlank() ? null : note);
        history.setChangedAt(changedAt);
        historyRepository.save(history);
    }

    /** #50：列表摘要——草稿长文本不进列表（按需从详情接口拉取），用 draftCount 支撑「n/3」标记。 */
    private ApplicationSummary summary(ApplicationRecord record) {
        return new ApplicationSummary(record.getId(), record.getJobDescriptionId(), record.getResumeVersionId(),
                record.getStatus(), record.getFeedbackText(), draftCount(record), record.getAppliedAt(),
                record.getNextFollowUpAt(), record.getVersion(), record.getCreatedAt(), record.getUpdatedAt());
    }

    private int draftCount(ApplicationRecord record) {
        int count = 0;
        if (present(record.getCoverLetterText())) count++;
        if (present(record.getEmailBodyText())) count++;
        if (present(record.getOpeningMessageText())) count++;
        return count;
    }

    private boolean present(String value) {
        return value != null && !value.isEmpty();
    }
}
