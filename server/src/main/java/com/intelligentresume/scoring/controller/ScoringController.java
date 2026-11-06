package com.intelligentresume.scoring.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
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
 * <p>小白三问：
 * <ul>
 *   <li><b>解决什么业务问题</b>：前端「算匹配度」「看历史评分结果」两个操作的 HTTP 入口，
 *       只做登录检查和转发。</li>
 *   <li><b>在链路哪里</b>：最外层；收请求 → 取登录用户 → 调 {@link ScoringService} →
 *       包统一 {@code ApiResponse} 返回。</li>
 *   <li><b>改坏它用户会看到什么</b>：查询端点放开 userId 过滤 → 任何人凭结果 id 能看别人的评分明细；
 *       端点改异步/AI → 违背「评分确定性、无配额」的产品承诺。</li>
 * </ul>
 *
 * <p>路由：
 * <ul>
 *   <li>POST {@code /api/scoring/match} — 计算并保存</li>
 *   <li>GET {@code /api/scoring/results/{id}} — 查询</li>
 * </ul>
 *
 * <p>要点：
 * <ul>
 *   <li>{@code userId} 只从请求属性取，取不到即 401；查询按 userId 过滤是跨用户隔离的唯一防线，不要在 service 之外再放开。</li>
 *   <li>两个端点都返回 {@code MatchResponse}，字段口径由 {@code ScoringService#toResponse} 统一。</li>
 *   <li>两条路由都是同步、纯计算，<b>不涉及任何 AI 配额或异步任务</b>。</li>
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
    public ResponseEntity<ApiResponse<MatchResponse>> getResult(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        MatchResponse result = scoringService.getResultResponse(id, userId);
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
