package com.intelligentresume.ai.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.task.domain.AiTaskType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预览令牌（R32）：它是「看到的 == 发出去的」唯一可自动校验的实现。
 *
 * <p>三条性质必须同时成立，缺一条这套机制就是装饰：
 * <ol>
 *   <li>同一输入 → 同一令牌（否则用户看完预览再提交永远对不上）；</li>
 *   <li>改了输入或改了脱敏开关 → 令牌变化（否则"预览后偷改内容"能蒙过去）；</li>
 *   <li>缺失/不符能被显式区分（前端要提示"请重新预览"，而不是笼统报错）。</li>
 * </ol>
 */
class AiPreviewServiceTest {

    private final AiPreviewService service = new AiPreviewService(new ObjectMapper());

    private Map<String, Object> input(String content) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("content", content);
        return input;
    }

    @Test
    void tokenIsStableForSameInput() {
        String first = service.tokenFor(AiTaskType.INLINE_OPTIMIZE, input("优化这段"));
        String second = service.tokenFor(AiTaskType.INLINE_OPTIMIZE, input("优化这段"));
        assertEquals(first, second);
    }

    @Test
    void tokenChangesWhenContentChanges() {
        String before = service.tokenFor(AiTaskType.INLINE_OPTIMIZE, input("优化这段"));
        String after = service.tokenFor(AiTaskType.INLINE_OPTIMIZE, input("优化另一段"));
        assertNotEquals(before, after, "预览后改了内容必须让旧令牌失效");
    }

    @Test
    void tokenChangesWhenContactToggleChanges() {
        Map<String, Object> redacted = input("a@b.com");
        String withoutContact = service.tokenFor(AiTaskType.INLINE_OPTIMIZE, redacted);

        Map<String, Object> withContact = input("a@b.com");
        withContact.put("_includeContact", true);
        String includingContact = service.tokenFor(AiTaskType.INLINE_OPTIMIZE, withContact);

        assertNotEquals(withoutContact, includingContact,
                "切换「包含联系方式」后实际发送内容不同 → 必须重新预览");
    }

    @Test
    void tokenDiffersPerTaskType() {
        assertNotEquals(service.tokenFor(AiTaskType.INLINE_OPTIMIZE, input("x")),
                service.tokenFor(AiTaskType.RESUME_OPTIMIZE, input("x")),
                "不同任务类型的系统提示不同，令牌不能通用");
    }

    @Test
    void verifyDistinguishesMissingStaleValid() {
        Map<String, Object> request = input("a@b.com");
        assertEquals(AiPreviewService.TokenCheck.MISSING, service.verify(AiTaskType.INLINE_OPTIMIZE, request));

        request.put(AiPreviewService.TOKEN_KEY, "deadbeef");
        assertEquals(AiPreviewService.TokenCheck.STALE, service.verify(AiTaskType.INLINE_OPTIMIZE, request));

        request.put(AiPreviewService.TOKEN_KEY, service.tokenFor(AiTaskType.INLINE_OPTIMIZE, request));
        assertEquals(AiPreviewService.TokenCheck.VALID, service.verify(AiTaskType.INLINE_OPTIMIZE, request));
    }

    @Test
    void previewShowsRedactedContentAndRedactionSummary() {
        AiPreviewService.PreviewResponse response =
                service.preview(new AiPreviewService.PreviewRequest(AiTaskType.INLINE_OPTIMIZE, input("联系 a@b.com"), null));

        String sent = String.join("\n", response.messages().stream().map(m -> m.get("content")).toList());
        assertFalse(sent.contains("a@b.com"), "预览里展示的就是实际会发送的内容（已脱敏）");
        assertTrue(sent.contains("[EMAIL]"));
        assertFalse(response.redactions().isEmpty(), "必须告诉用户本轮替换掉了什么");
        assertFalse(response.previewToken().isBlank());
    }

    @Test
    void includeContactFlagInRequestIsHonoured() {
        AiPreviewService.PreviewResponse response = service.preview(
                new AiPreviewService.PreviewRequest(AiTaskType.INLINE_OPTIMIZE, input("联系 a@b.com"), true));

        String sent = String.join("\n", response.messages().stream().map(m -> m.get("content")).toList());
        assertTrue(sent.contains("a@b.com"));
        assertTrue(response.includeContact());
        assertTrue(response.redactions().isEmpty());
    }
}
