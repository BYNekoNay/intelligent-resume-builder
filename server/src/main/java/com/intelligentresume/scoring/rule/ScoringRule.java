package com.intelligentresume.scoring.rule;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public interface ScoringRule {

    String name();

    BigDecimal score(Set<String> jdTokens, Set<String> resumeTokens, java.util.Map<String, Object> jdMeta);

    List<String> matched(Set<String> jdTokens, Set<String> resumeTokens);

    List<String> partialMatched(Set<String> jdTokens, Set<String> resumeTokens);

    List<String> missing(Set<String> jdTokens, Set<String> resumeTokens);
}