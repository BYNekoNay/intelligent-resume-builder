package com.intelligentresume.interview.dto;
import java.util.List;
import java.util.Map;
public final class InterviewResponses { private InterviewResponses(){}
 public record Start(Long interviewId,String firstQuestion,String status){}
 public record Answer(int roundScore,Map<String,List<String>> feedback,String nextQuestion){}
 public record Report(int totalScore,String summary,List<String> strengths,List<String> weaknesses,List<String> resumeSuggestions,List<String> expressionSuggestions){}
}
