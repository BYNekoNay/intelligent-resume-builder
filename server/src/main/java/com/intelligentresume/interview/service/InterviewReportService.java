package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.CompletionReason;
import com.intelligentresume.interview.domain.EvaluationSource;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.dto.InterviewReportResponse;
import com.intelligentresume.interview.dto.InterviewStateResponse;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 面试结束与报告聚合（单事务）。
 *
 * <p>【小白导读】
 * ① 业务问题：面试打完了，用户要看「考得怎么样」——总分、五个维度（相关性/证据具体度/
 *    结构清晰度/岗位胜任力/真实性反思）的平均分、每轮问答明细、跨轮提炼的亮点与短板。
 *    这里负责把已落库的每轮记录汇总成这份报告；以及用户主动点「结束面试」的收尾。
 * ② 链路位置：上游是 {@code InterviewService} 的 finish/report；下游只读
 *    {@code interview_record}（逐轮记录）与 {@code interview_session}（会话），
 *    产出 {@link InterviewReportResponse} 给前端报告页。不触发任何新 AI 调用。
 * ③ 改坏它：报告分数算错（比如把平均写成求和，总分直接爆表）、用户面试没结束也能看报告、
 *    或进行中的面试被误结束。
 *
 * <p>finish / report 使用方法级 @Transactional（原样迁移），不混用 TransactionTemplate。
 *
 * <p>术语速查：五维 —— 每轮点评由模型按五个维度打分（25+25+20+20+10，满分 100），
 * 报告里的五维分就是各轮的平均；评估来源 —— 每轮分数可能来自 AI 也可能来自规则降级，
 * 报告会标明 AI / RULE / MIXED。
 *
 * <p>要点：
 * <ul>
 *   <li>finish 是「用户主动收尾」：至少完成 1 题，且不能在有 AI 操作进行中时结束。</li>
 *   <li>report 只读且只接受已完成会话；报告完全由已有 {@code interview_record} 聚合，不触发新的 AI 调用。</li>
 *   <li>总分为各轮 roundScore 的算术平均（四舍五入），不是求和；五维也同样取平均。</li>
 *   <li>来源标记按「AI 轮数 / 规则轮数」推成 AI / RULE / MIXED，前端据此提示评估可信度。</li>
 * </ul>
 */
@Service
public class InterviewReportService {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewRecordRepository recordRepository;
    private final InterviewStateAssembler stateAssembler;

    public InterviewReportService(InterviewSessionRepository sessionRepository,
                                  InterviewRecordRepository recordRepository,
                                  InterviewStateAssembler stateAssembler) {
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
        this.stateAssembler = stateAssembler;
    }

    /** 用户主动结束面试：已结束则幂等返回；AI 进行中拒绝；至少完成 1 题。 */
    @Transactional
    public InterviewStateResponse finish(Long id, Long userId) {
        InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                .orElseThrow(() -> stateAssembler.notFound("面试会话不存在"));

        if (session.getStatus() == InterviewStatus.COMPLETED) {
            return stateAssembler.buildStateResponse(session, null, null);
        }
        if (session.getStatus() == InterviewStatus.GENERATING_QUESTION
                || session.getStatus() == InterviewStatus.EVALUATING_ANSWER) {
            throw new BusinessException(ErrorCode.CONFLICT, "Cannot finish while an AI operation is in progress");
        }

        long completedCount = recordRepository.countBySessionId(session.getId());
        if (completedCount < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "至少完成 1 题后才能结束面试");
        }

        session.setStatus(InterviewStatus.COMPLETED);
        session.setCurrentQuestion(null);
        session.setCompletionReason(CompletionReason.USER_FINISHED);
        sessionRepository.save(session);
        return stateAssembler.buildStateResponse(session, null, null);
    }

    /**
     * 生成复盘报告。只允许对已完成的会话调用。
     *
     * <p>【小白导读】五维评分的算法（也是全模块唯一的汇总口径）：①取会话全部轮次记录
     * → ②总分 = 各轮 roundScore 的平均分四舍五入 → ③五维各自「逐条累加后除以记录总数」
     * 取平均 → ④亮点/短板/建议按 key 跨轮去重（保序、最多 5 条）→ ⑤逐轮明细原样拼装。
     * 注意总分与五维的除数都是记录总数；区别只在每轮 roundScore 必有值，而五维可能因
     * 历史数据缺 dimensionScores 被按 0 计入平均、拉低均值（下方原有注释说明的刻意行为）。
     *
     * <p>没有任何记录时返回一份「空报告」而不是抛错：会话可能在完成前被异常推进，
     * 前端仍需要一个可渲染的结构。
     */
    @Transactional(readOnly = true)
    public InterviewReportResponse report(Long id, Long userId) {
        InterviewSession session = stateAssembler.owned(id, userId);

        if (session.getStatus() != InterviewStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.CONFLICT, "面试未完成");
        }

        List<InterviewRecord> records = recordRepository.findBySessionIdOrderByRoundNoAscIdAsc(session.getId());

        if (records.isEmpty()) {
            return new InterviewReportResponse(0, "尚未完成任何回答", List.of(), List.of(), List.of(), List.of(),
                    null, 0, 0, null, null, 0, 0, List.of());
        }

        List<InterviewReportResponse.RoundDetail> rounds = records.stream().map(this::roundDetail).toList();

        int total = (int) Math.round(records.stream().mapToInt(InterviewRecord::getRoundScore).average().orElse(0));

        // 五维聚合：逐条累加后再除以「记录总数 n」（而不是有维度数据的条数），
        // 因此缺 dimensionScores 的记录会以 0 参与平均，拉低均值
        double sumRel = 0, sumEvid = 0, sumStruct = 0, sumRole = 0, sumAuth = 0;
        int aiCount = 0, ruleCount = 0;
        for (InterviewRecord r : records) {
            Object dims = r.getFeedbackJson().get("dimensionScores");
            if (dims instanceof Map<?, ?> m) {
                sumRel += stateAssembler.num(m.get("relevance"));
                sumEvid += stateAssembler.num(m.get("evidenceSpecificity"));
                sumStruct += stateAssembler.num(m.get("structureClarity"));
                sumRole += stateAssembler.num(m.get("roleCompetency"));
                sumAuth += stateAssembler.num(m.get("authenticityReflection"));
            }
            if (r.getEvaluationSource() == EvaluationSource.RULE) ruleCount++;
            else aiCount++;
        }
        int n = records.size();
        int dRel = (int) Math.round(sumRel / n);
        int dEvid = (int) Math.round(sumEvid / n);
        int dStruct = (int) Math.round(sumStruct / n);
        int dRole = (int) Math.round(sumRole / n);
        int dAuth = (int) Math.round(sumAuth / n);

        InterviewReportResponse.DimensionScores dimensionScores =
                new InterviewReportResponse.DimensionScores(dRel, dEvid, dStruct, dRole, dAuth);

        // 报告来源类型：任意一轮来自规则即标 MIXED，否则按是否有 AI 轮次决定
        EvaluationSource reportSource;
        if (aiCount > 0 && ruleCount > 0) reportSource = EvaluationSource.MIXED;
        else if (aiCount > 0) reportSource = EvaluationSource.AI;
        else reportSource = EvaluationSource.RULE;

        return new InterviewReportResponse(
                total, "已完成 %d 轮回答，平均得分 %d 分。面试已结束。".formatted(n, total),
                stateAssembler.distinctFeedback(records, "strengths"),
                stateAssembler.distinctFeedback(records, "improvements"),
                stateAssembler.distinctFeedback(records, "resumeSuggestions"),
                stateAssembler.distinctFeedback(records, "expressionSuggestions"),
                dimensionScores,
                session.getTargetQuestionCount(), n,
                session.getCompletionReason(), reportSource,
                aiCount, ruleCount,
                rounds
        );
    }

    @SuppressWarnings("unchecked")
    private InterviewReportResponse.RoundDetail roundDetail(InterviewRecord record) {
        Map<String, Object> feedback = record.getFeedbackJson();
        InterviewReportResponse.DimensionScores dims = null;
        Object rawDims = feedback.get("dimensionScores");
        if (rawDims instanceof Map<?, ?> m) {
            dims = new InterviewReportResponse.DimensionScores(
                    stateAssembler.num(m.get("relevance")),
                    stateAssembler.num(m.get("evidenceSpecificity")),
                    stateAssembler.num(m.get("structureClarity")),
                    stateAssembler.num(m.get("roleCompetency")),
                    stateAssembler.num(m.get("authenticityReflection")));
        }
        return new InterviewReportResponse.RoundDetail(
                record.getId(), record.getRoundNo(), record.getQuestionText(), record.getAnswerText(), record.getRoundScore(),
                dims,
                stringList(feedback.get("strengths")),
                stringList(feedback.get("improvements")),
                feedback.get("suggestedAnswer") instanceof String suggested ? suggested : null,
                record.getEvaluationSource());
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }
}
