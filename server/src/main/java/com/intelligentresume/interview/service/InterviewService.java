package com.intelligentresume.interview.service;

import com.intelligentresume.ai.consent.service.ConsentService;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.dto.InterviewAnswerRequest;
import com.intelligentresume.interview.dto.InterviewResponses;
import com.intelligentresume.interview.dto.InterviewStartRequest;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

@Service
public class InterviewService {
    private final ConsentService consentService; private final InterviewSessionRepository sessionRepository;
    private final InterviewRecordRepository recordRepository; private final ResumeVersionRepository versionRepository;
    private final ResumeRepository resumeRepository; private final JobDescriptionRepository jobRepository;
    public InterviewService(ConsentService c,InterviewSessionRepository s,InterviewRecordRepository r,ResumeVersionRepository v,ResumeRepository rr,JobDescriptionRepository j){consentService=c;sessionRepository=s;recordRepository=r;versionRepository=v;resumeRepository=rr;jobRepository=j;}
    @Transactional
    public InterviewResponses.Start start(InterviewStartRequest request,Long userId){
        requireConsent(userId); jobRepository.findByIdAndUserId(request.jobDescriptionId(),userId).orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
        if(request.sourceType()==InterviewSession.SourceType.PLATFORM_RESUME){if(request.resumeVersionId()==null)throw new BusinessException(ErrorCode.VALIDATION);ResumeVersion v=versionRepository.findById(request.resumeVersionId()).orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));resumeRepository.findByIdAndUserId(v.getResumeId(),userId).orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));}
        if(request.sourceType()==InterviewSession.SourceType.EXTERNAL_RESUME&&(request.externalResumeText()==null||request.externalResumeText().isBlank()))throw new BusinessException(ErrorCode.VALIDATION);
        InterviewSession s=new InterviewSession();s.setUserId(userId);s.setSourceType(request.sourceType());s.setResumeVersionId(request.resumeVersionId());s.setExternalResumeText(request.externalResumeText());s.setJobDescriptionId(request.jobDescriptionId());s.setInterviewMode(request.interviewMode());s.setStatus(InterviewSession.Status.ACTIVE);s.setCurrentQuestion("请结合真实经历，介绍一个最能证明你适合该岗位的项目。");s=sessionRepository.save(s);return new InterviewResponses.Start(s.getId(),s.getCurrentQuestion(),s.getStatus().name());
    }
    @Transactional
    public InterviewResponses.Answer answer(Long id,InterviewAnswerRequest request,Long userId){
        requireConsent(userId);InterviewSession s=owned(id,userId);if(s.getStatus()==InterviewSession.Status.COMPLETED)throw new BusinessException(ErrorCode.CONFLICT,"面试会话已结束");List<InterviewRecord> existing=recordRepository.findBySessionIdOrderByCreatedAtAsc(id);int score=Math.min(95,60+Math.min(request.answer().trim().length(),70)/2);Map<String,List<String>> feedback=Map.of("strengths",List.of("回答基于用户提交的真实内容"),"improvements",List.of("建议补充背景、行动和可核实结果"));InterviewRecord r=new InterviewRecord();r.setSessionId(id);r.setQuestionText(s.getCurrentQuestion());r.setAnswerText(request.answer().trim());r.setRoundScore(score);r.setFeedbackJson(Map.of("strengths",feedback.get("strengths"),"improvements",feedback.get("improvements")));recordRepository.save(r);String next=existing.size()>=2?null:"请说明你在该经历中遇到的最大困难，以及你采取的具体行动。";if(next==null){s.setStatus(InterviewSession.Status.COMPLETED);s.setCurrentQuestion("面试已完成");}else{s.setCurrentQuestion(next);}sessionRepository.save(s);return new InterviewResponses.Answer(score,feedback,next);
    }
    public InterviewResponses.Report report(Long id,Long userId){InterviewSession s=owned(id,userId);List<InterviewRecord> records=recordRepository.findBySessionIdOrderByCreatedAtAsc(id);int total=records.isEmpty()?0:(int)Math.round(records.stream().mapToInt(InterviewRecord::getRoundScore).average().orElse(0));return new InterviewResponses.Report(total,records.isEmpty()?"尚未提交回答":"已完成 "+records.size()+" 轮回答",List.of("回答能联系个人经历"),List.of("量化结果和技术细节仍可加强"),List.of("将确认后的项目结果补充到简历"),List.of("使用背景—行动—结果结构组织回答"));}
    private InterviewSession owned(Long id,Long userId){return sessionRepository.findByIdAndUserId(id,userId).orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));}
    private void requireConsent(Long userId){if(!consentService.isConsented(userId))throw new BusinessException(ErrorCode.CONSENT_REQUIRED);}
}
