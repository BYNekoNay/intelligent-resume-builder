package com.intelligentresume.ai.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.provider.AiMessageAssembler;
import com.intelligentresume.ai.provider.ContactRedactor;
import com.intelligentresume.ai.task.domain.AiTaskType;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 发送前预览（R32）：算出「将要发送的确切内容」并给出可校验的令牌。
 *
 * <p>两条硬约束：
 * <ol>
 *   <li><b>只读</b>：不写库、不调 provider —— 预览本身不能产生任何副作用，
 *       否则"取消预览"就不再是"一条都没发"；</li>
 *   <li><b>可校验</b>：令牌是对同一份组装结果的哈希。创建任务时服务端重算一次，
 *       对不上就拒绝 —— 这是"看到的 == 发出去的"唯一能自动验证的实现方式。</li>
 * </ol>
 *
 * <p>令牌不参与哈希输入（它本身不从 content 派生），因此没有循环依赖；
 * 是否包含联系方式参与哈希，所以用户切换开关后旧令牌自动失效。
 */
@Service
public class AiPreviewService {

    /** 客户端把预览令牌放在 input 的保留键里（下划线开头，不会进入 prompt 正文）。 */
    public static final String TOKEN_KEY = "_previewToken";

    private final ObjectMapper objectMapper;

    public AiPreviewService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record PreviewRequest(AiTaskType taskType, Map<String, Object> input, Boolean includeContact) {
    }

    public record PreviewResponse(AiTaskType taskType,
                                  List<Map<String, String>> messages,
                                  boolean includeContact,
                                  List<ContactRedactor.Redaction> redactions,
                                  String previewToken) {
    }

    public PreviewResponse preview(PreviewRequest request) {
        AiTaskType type = request.taskType();
        Map<String, Object> input = withContactFlag(request.input(), Boolean.TRUE.equals(request.includeContact()));
        AiMessageAssembler.PreviewPayload payload = AiMessageAssembler.preview(type, input);
        return new PreviewResponse(type, payload.messages(), payload.includeContact(),
                payload.redactions(), tokenFor(type, input));
    }

    /** 计算令牌：同一 (taskType, 脱敏开关, messages) 必然得到同一个值。 */
    public String tokenFor(AiTaskType type, Map<String, Object> input) {
        Map<String, Object> safeInput = input != null ? input : Map.of();
        AiMessageAssembler.PreviewPayload payload = AiMessageAssembler.preview(type, safeInput);
        String canonical = canonicalize(type, payload);
        return sha256Hex(canonical);
    }

    /**
     * 校验客户端提供的令牌。返回 {@link TokenCheck}，由调用方决定抛什么业务错误
     * （这里不直接抛，便于单测覆盖三种分支而不依赖异常类型）。
     */
    public TokenCheck verify(AiTaskType type, Map<String, Object> input) {
        Object provided = input != null ? input.get(TOKEN_KEY) : null;
        if (!(provided instanceof String token) || token.isBlank()) {
            return TokenCheck.MISSING;
        }
        String expected = tokenFor(type, input);
        return expected.equals(token) ? TokenCheck.VALID : TokenCheck.STALE;
    }

    public enum TokenCheck {
        VALID, MISSING, STALE
    }

    // ------------------------------------------------------------------ 内部

    /** 把 includeContact 落进 input（真正决定发送内容的是 input 里的键）。 */
    private Map<String, Object> withContactFlag(Map<String, Object> input, boolean includeContact) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(input != null ? input : Map.of());
        copy.put(AiMessageAssembler.INCLUDE_CONTACT_KEY, includeContact);
        return copy;
    }

    private String canonicalize(AiTaskType type, AiMessageAssembler.PreviewPayload payload) {
        try {
            // messages 是 List<Map>，Jackson 的键序对 LinkedHashMap 稳定；再拼上类型与开关，
            // 保证"同一输入同一脱敏策略"得到同一令牌、"改了任一处"得到不同令牌
            return type.name() + "|" + payload.includeContact() + "|" + objectMapper.writeValueAsString(payload.messages());
        } catch (Exception exception) {
            throw new IllegalStateException("预览载荷序列化失败", exception);
        }
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }
}
