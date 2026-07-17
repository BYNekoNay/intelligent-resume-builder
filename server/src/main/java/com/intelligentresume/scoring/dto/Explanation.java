package com.intelligentresume.scoring.dto;

import java.util.List;

public record Explanation(
        List<String> matched,
        List<String> partialMatched,
        List<String> missing,
        List<String> suggestions,
        String disclaimer
) {}