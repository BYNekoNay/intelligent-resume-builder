package com.intelligentresume.contract;

import com.intelligentresume.application.domain.ApplicationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投递状态迁移矩阵门禁：**以后端 {@code ApplicationService.TRANSITIONS} 为基准**，
 * 逐格对账其余每一份手工副本。
 *
 * <p>背景（本批实测漏检）：后端加了一条 {@code NO_RESPONSE → REJECTED}，前端镜像表没跟，
 * 结果是「后端允许、UI 不提供」——这条边等于白加，而当时全部门禁都看不见：
 * {@link FrontendEnumContractTest} 只比对**取值集合**（9 个状态名），比不了**迁移矩阵**；
 * 后端单测/IT 又只覆盖后端一侧。这是「同一事实多处维护、只改一处」的缺陷家族，故固化成机器判据。
 *
 * <p>三份副本、两条用例：后端 {@code TRANSITIONS}、前端 {@code transitions}、
 * {@code docs/05} §2.7 的状态机表格（该表在文档里被声明为「机器可读契约」）。文档那份是本批新增的，
 * 起草时就出过一次错（区间式写法隐含了并不存在的 {@code INTERVIEWING→SCREENING} 边），人工自查才发现。
 *
 * <p>解析器在 {@link StatusMatrixSources}（三份副本的锚点与失效语义各不相同，单独成类以免本类越过
 * 300 行门禁）。**解析不到一律红，绝不 skip**：两个空集合「相等」而静默通过是本类最危险的失效方式，
 * 故 {@link #assertParsed} 在三处设卡（状态数必须等于枚举值个数、每个状态的目标集合非空、报错即红）。
 */
class ApplicationStatusMatrixContractTest {

    /** 与迁移矩阵必须覆盖的状态数一致；防止任一侧残缺后「对等的残缺」被判为通过。 */
    private static final int EXPECTED_STATUS_COUNT = ApplicationStatus.values().length;

    @Test
    @DisplayName("投递状态迁移矩阵：前端 transitions 与后端 TRANSITIONS 逐格一致")
    void frontendTransitionMatrixMatchesBackend() throws Exception {
        assertMatrixMatches("前端 transitions", StatusMatrixSources.frontend(),
                "前后端投递状态迁移矩阵不一致 —— 同一份迁移规则两处手工维护，"
                        + "任一处漏改都会让某条迁移边「后端允许但 UI 不提供」（用户点不到，等于白加），"
                        + "或「UI 提供但后端 409」（用户点了必失败）。处置：对齐两侧，"
                        + "以后端 ApplicationService.TRANSITIONS 为准");
    }

    @Test
    @DisplayName("投递状态迁移矩阵：docs/05 迁移表与后端 TRANSITIONS 逐格一致")
    void docsTransitionMatrixMatchesBackend() throws Exception {
        assertMatrixMatches("docs/05", StatusMatrixSources.docs(),
                "docs/05 的迁移表与后端实现不一致 —— 该表被文档明确声明为「机器可读契约」并被本门禁解析，"
                        + "读者会按它理解可迁移范围；文档偏一格等于给出错误的 API 契约。"
                        + "处置：改文档（表格口径以后端 ApplicationService.TRANSITIONS 为准）");
    }

    /** 共用对账：以后端为基准，报出「哪一侧、哪个状态、哪条边」，顺序无关。 */
    private void assertMatrixMatches(String side, Map<ApplicationStatus, Set<ApplicationStatus>> actual,
                                     String advice) {
        Map<ApplicationStatus, Set<ApplicationStatus>> backend = StatusMatrixSources.backend();
        assertParsed("后端 TRANSITIONS", backend);
        assertParsed(side, actual);
        assertSameStatuses(side, backend.keySet(), actual.keySet());

        List<String> problems = new ArrayList<>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            Set<ApplicationStatus> expected = backend.get(status);
            Set<ApplicationStatus> actualTargets = actual.get(status);
            if (!expected.equals(actualTargets)) {
                problems.add("  " + status + "：仅后端允许 " + difference(expected, actualTargets)
                        + "；仅 " + side + " 允许 " + difference(actualTargets, expected));
            }
        }
        assertTrue(problems.isEmpty(), advice + "：\n" + String.join("\n", problems));
    }

    /**
     * 解析失败必须红。两个空集合「相等」而静默通过是本门禁最危险的失效方式，
     * 故在三处设卡：状态数等于枚举值个数、每个状态的目标集合非空、报错信息带侧别。
     */
    private void assertParsed(String side, Map<ApplicationStatus, Set<ApplicationStatus>> matrix) {
        assertEquals(EXPECTED_STATUS_COUNT, matrix.size(),
                side + " 解析出 " + matrix.size() + " 个状态，应为 " + EXPECTED_STATUS_COUNT
                        + " —— 只要不是全部状态，说明清单缺项或门禁解析逻辑已失效，"
                        + "请同步更新门禁解析逻辑后再交付");
        for (Map.Entry<ApplicationStatus, Set<ApplicationStatus>> entry : matrix.entrySet()) {
            assertTrue(!entry.getValue().isEmpty(),
                    side + " 的状态 " + entry.getKey() + " 目标集合为空 —— 状态机至少应保留自环，"
                            + "空集合意味着解析失效（空 vs 空会被判为相等而静默通过）");
        }
    }

    private void assertSameStatuses(String side, Set<ApplicationStatus> backend, Set<ApplicationStatus> actual) {
        assertEquals(names(backend), names(actual),
                "后端与 " + side + " 覆盖的状态集合不一致：仅后端有 " + difference(backend, actual)
                        + "；仅 " + side + " 有 " + difference(actual, backend));
    }

    private Set<String> names(Set<ApplicationStatus> statuses) {
        Set<String> sorted = new TreeSet<>();
        if (statuses != null) {
            for (ApplicationStatus status : statuses) {
                sorted.add(status.name());
            }
        }
        return sorted;
    }

    private String difference(Set<ApplicationStatus> left, Set<ApplicationStatus> right) {
        if (left == null) return "（无）";
        Set<ApplicationStatus> diff = EnumSet.noneOf(ApplicationStatus.class);
        diff.addAll(left);
        diff.removeAll(right == null ? EnumSet.noneOf(ApplicationStatus.class) : right);
        String joined = String.join(", ", names(diff));
        return joined.isEmpty() ? "（无）" : joined;
    }
}
