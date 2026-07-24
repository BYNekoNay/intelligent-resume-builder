package com.intelligentresume.scoring.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.scoring.domain.MatchResult;
import com.intelligentresume.scoring.dto.MatchRequest;
import com.intelligentresume.scoring.dto.MatchResponse;
import com.intelligentresume.scoring.service.ScoringService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 评分控制器。纯规则计算，不调用 LLM。
 *
 * <p>路由：
 * <ul>
 *   <li>POST {@code /api/scoring/match} — 计算并保存</li>
 *   <li>GET {@code /api/scoring/results/{id}} — 查询</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/scoring")
public class ScoringController {

    private final ScoringService scoringService;

    public ScoringController(ScoringService scoringService) {
        this.scoringService = scoringService;
    }

    /**
     * 计算 JD 规则覆盖度并保存。
     */
    @PostMapping("/match")
    public ResponseEntity<ApiResponse<MatchResponse>> match(
            @Valid @RequestBody MatchRequest request,
            HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        MatchResponse response = scoringService.score(request, userId);
        return ResponseEntity.ok(ApiResponse.success(response, traceId(httpRequest)));
    }

    /**
     * 查询评分结果。
     */
    @GetMapping("/results/{id}")
    public ResponseEntity<ApiResponse<MatchResult>> getResult(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        MatchResult result = scoringService.getResult(id, userId);
        return ResponseEntity.ok(ApiResponse.success(result, traceId(httpRequest)));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object attr = request.getAttribute("currentUserId");
        if (attr == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return (Long) attr;
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
