package com.intelligentresume.ai.generation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * 草稿 Schema 校验器。校验 AI 输出的结构化草稿是否符合约定格式。
 *
 * <p>新人三问：①业务上它是"模型产出的质检闸门"——模型返回的 JSON 我们一律不信，
 * 结构不对、引用了确认范围之外的资料（幻觉 = 模型编造出不存在的 ID 或内容）、体积超限，都在这里拦下；
 * ②它在链路的「模型输出之后、写结果之前」：{@code JobGenerationService} 两条路径在拿到草稿后都调它；
 * ③改坏它用户会看到：要么垃圾草稿直接进确认页（校验过松），要么正常草稿被误杀、
 * 生成全部失败（校验过严，比如版本常量与配置不同步，见下）。
 *
 * <p>规则:
 * <ol>
 *   <li>顶层键必须是支持的简历内容模块</li>
 *   <li>要求溯源的节点必须包含 _source/_sources 与 _pending 中的**恰好一个**；
 *       basics / objective 由个人档案派生，不作此要求</li>
 *   <li>数组元素必须包含 _source 或 _pending 之一(互斥)</li>
 *   <li>序列化字节数 ≤ max-output-bytes</li>
 *   <li>schemaVersion 不匹配抛 VALIDATION</li>
 * </ol>
 *
 * <p>要点（红线）：
 * <ul>
 *   <li>第 2 条的互斥只对**要求溯源**的节点生效；{@code basics}/{@code objective} 来自已确认的
 *       个人档案，本来就没有 materialId 可引，对它们强制互斥会让整份草稿作废（线上踩过）。</li>
 *   <li>{@link #CURRENT_VERSION} 是硬编码常量，而调用方传进来的 {@code schemaVersion} 来自配置
 *       {@code app.ai.generation.schema-version}。两处必须同步改 —— 只改配置不改常量，
 *       所有生成任务都会在这里被判 400。</li>
 *   <li>传入 {@code allowedMaterialIds} 时，草稿引用的每个 materialId 都必须在确认快照内，
 *       用于挡住模型幻觉出来的 ID；传 null 则跳过这道检查（两参重载目前只有测试在用）。</li>
 *   <li>本类只校验结构与引用，不校验文案质量；生产调用方（{@code JobGenerationService} 两条路径）
 *       会把这里的异常统一改写成 {@code AI_FAILURE}，所以对外看到的不是 VALIDATION。</li>
 * </ul>
 */
@Component
public class JobGenerationSchemaValidator {

    static final String CURRENT_VERSION = "v1.0.0";
    static final Set<String> SUPPORTED_SECTIONS =
            Set.of("basics", "work", "education", "skills", "projects", "certificates",
                    "objective", "volunteering", "courses", "publications", "customSections");

    private final int maxOutputBytes;
    private final ObjectMapper objectMapper;

    public JobGenerationSchemaValidator(
            @Value("${app.ai.generation.max-output-bytes:65536}") int maxOutputBytes,
            ObjectMapper objectMapper) {
        this.maxOutputBytes = maxOutputBytes;
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unchecked")
    public void validate(Map<String, Object> draft, String schemaVersion) {
        validate(draft, schemaVersion, null);
    }

    /**
     * 校验顺序固定：版本 → 空草稿 → 顶层键白名单 → 逐节点溯源 → 字节数。
     *
     * <p>{@code basics} / {@code objective} 传 {@code requiresProvenance=false}：它们由
     * 已确认的个人档案派生，没有 materialId 可引。
     */
    public void validate(Map<String, Object> draft, String schemaVersion, Set<Long> allowedMaterialIds) {
        if (!CURRENT_VERSION.equals(schemaVersion)) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "Schema 版本不匹配, 当前: " + CURRENT_VERSION + ", 传入: " + schemaVersion);
        }
        if (draft == null || draft.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "草稿为空");
        }

        // 1. 顶层键校验
        for (String key : draft.keySet()) {
            if (!SUPPORTED_SECTIONS.contains(key)) {
                throw new BusinessException(ErrorCode.VALIDATION, "不允许的顶层字段: " + key);
            }
        }

        // 2. 递归校验 _source/_pending
        for (Map.Entry<String, Object> entry : draft.entrySet()) {
            // basics can be derived from the confirmed personal-profile snapshot.
            // It is not a career material and therefore has no materialId to cite.
            validateValue(entry.getValue(), entry.getKey(), !"basics".equals(entry.getKey()) && !"objective".equals(entry.getKey()), allowedMaterialIds);
        }

        // 3. 字节数校验
        checkByteSize(draft);
    }

    /**
     * 递归遍历草稿。{@code requiresProvenance} 的传递规则是**刻意不对称**的：
     * Map 的子节点一律传 false（溯源只在"条目"层级要求，嵌套字段不该各自再带一份），
     * List 的元素一律传 true（每个条目都是可引用的最小单元）。
     *
     * <p>List 分支在递归前先单独查一次元素的 {@code _source}/{@code _pending}，是为了给出
     * 带下标的报错（能直接定位到第几条），递归里还会再查一次同样的条件。
     */
    @SuppressWarnings("unchecked")
    private void validateValue(Object value, String path, boolean requiresProvenance, Set<Long> allowedMaterialIds) {
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            boolean hasSource = map.containsKey("_source") || map.containsKey("_sources");
            boolean hasPending = map.containsKey("_pending");
            // 互斥只对「要求溯源」的节点生效。
            //
            // basics / objective 由已确认的个人档案派生，本就**不要求**携带溯源信息
            //（见 validate 中对这两个键的 requiresProvenance=false，以及 existing test
            // basicsMayUseConfirmedPersonalProfileWithoutMaterialProvenance）。
            // 模型偶尔在同一对象上既写 _sources 又写 _pending 时，对这类节点并不构成契约破坏；
            // 此前无条件互斥会把整份草稿判为失败，导致岗位简历生成整体不可用（线上实测踩到）。
            //
            // 严格性不变：要求溯源的节点下方 `requiresProvenance && hasSource == hasPending`
            // 分支同样会拦住「两个都写」（此时 hasSource == hasPending 成立）。
            if (requiresProvenance && hasSource && hasPending) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        path + ": _source 与 _pending 不能同时存在");
            }
            // 递归校验子节点
            if (requiresProvenance && hasSource == hasPending) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        path + ": must include exactly one of _source, _sources, or _pending");
            }
            if (hasSource && allowedMaterialIds != null && !"basics".equals(path) && !"objective".equals(path)) {
                validateSourceIds(map, path, allowedMaterialIds);
            }
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!entry.getKey().startsWith("_")) {
                    validateValue(entry.getValue(), path + "." + entry.getKey(), false, allowedMaterialIds);
                }
            }
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Map) {
                    Map<String, Object> mapItem = (Map<String, Object>) item;
                    boolean hasSource = mapItem.containsKey("_source") || mapItem.containsKey("_sources");
                    boolean hasPending = mapItem.containsKey("_pending");
                    if (hasSource == hasPending) {
                        throw new BusinessException(ErrorCode.VALIDATION,
                                path + "[" + i + "]: 数组元素必须包含 _source 或 _pending 之一");
                    }
                }
                validateValue(item, path + "[" + i + "]", true, allowedMaterialIds);
            }
        }
    }

    /**
     * 收集该节点引用的全部 materialId（{@code _sources} 复数形态与 {@code _source} 单数旧形态都收），
     * 然后要求：至少有一个 ID，且全部落在本次确认的快照里。
     *
     * <p>注意 {@code _sources} 里任何一项不是对象、或 materialId 缺失/非正数，都会整份草稿判失败 ——
     * 这里不做"跳过坏项继续"的容错。
     */
    @SuppressWarnings("unchecked")
    private void validateSourceIds(Map<String, Object> map, String path, Set<Long> allowedMaterialIds) {
        Set<Long> sourceIds = new HashSet<>();
        Object sources = map.get("_sources");
        if (sources instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> source)) {
                    throw new BusinessException(ErrorCode.VALIDATION, path + ": _sources entries must be objects");
                }
                addSourceId(((Map<String, Object>) source).get("materialId"), path, sourceIds);
            }
        }
        Object legacy = map.get("_source");
        if (legacy instanceof Map<?, ?> source) {
            addSourceId(((Map<String, Object>) source).get("materialId"), path, sourceIds);
        }
        if (sourceIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, path + ": source metadata must contain a materialId");
        }
        if (!allowedMaterialIds.containsAll(sourceIds)) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    path + ": source is not in the confirmed material snapshot");
        }
    }

    private void addSourceId(Object value, String path, Set<Long> sourceIds) {
        Long id = null;
        if (value instanceof Number number) id = number.longValue();
        else if (value != null) {
            try { id = Long.parseLong(value.toString()); } catch (NumberFormatException ignored) { }
        }
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, path + ": source materialId is required");
        }
        sourceIds.add(id);
    }

    /** 上限来自 {@code app.ai.generation.max-output-bytes}；序列化本身就是 JSON 合法性的一次隐式检查。 */
    private void checkByteSize(Map<String, Object> draft) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(draft);
            if (bytes.length > maxOutputBytes) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "草稿大小 " + bytes.length + " 字节超过上限 " + maxOutputBytes);
            }
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "草稿序列化失败");
        }
    }
}
