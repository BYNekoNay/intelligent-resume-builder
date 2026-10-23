package com.intelligentresume.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导出包的**稳定 ID 注入与清单装配**（R37），从 {@code AccountExportService} 拆出 ——
 * 那个类已顶到行数棘轮，且这两件事与"读哪些实体"无关，单独放更清楚。
 *
 * <h2>两个必须写清的坑（2026-10-23 两次实测踩出来的）</h2>
 * <ol>
 *   <li><b>不能用 {@code instanceof Map} 判断记录</b>：Jackson 2.x 的 {@code ObjectNode} 只实现
 *       {@code java.io.Serializable}（{@code javap} 实测），<b>不实现</b> {@code java.util.Map}。
 *       原代码在 {@code injectStableIds} 与 {@code buildManifest} 各写了一次同样的判断，
 *       于是「记录缺 {@code stableId}」与「清单 {@code stableIds} 为空」同时发生：
 *       导出文件里一条稳定 ID 都没有，桌面端 {@code DesktopImportService#preview} 立刻以
 *       「包内记录缺少 stableId」**拒绝整份导入** —— Web→桌面迁移对真实包 100% 失败。
 *       而所有既有测试都是手写带 stableId 的包，所以长期全绿。</li>
 *   <li><b>不能经 {@code valueToTree} 中转后再改</b>：那会复制出新的节点树，
 *       往副本里写 {@code stableId} 不会回到负载上。必须遍历并修改**原节点**。</li>
 * </ol>
 *
 * <p>守护用例：{@code DesktopExportImportRoundTripIT}（穿真实导出链路）。
 */
final class ExportPackageAssembler {

    private final ObjectMapper objectMapper;
    private final String source;
    private final List<String> collections;

    ExportPackageAssembler(ObjectMapper objectMapper, String source, List<String> collections) {
        this.objectMapper = objectMapper;
        this.source = source;
        this.collections = collections;
    }

    /**
     * 把实体列表转成 JSON 节点列表。
     *
     * <p>必须先转换：{@link #injectStableIds} 只认 JSON 节点，直接放 JPA 实体会让整类集合
     * 拿不到稳定 ID（实体同样不实现 {@code Map}）。
     */
    List<ObjectNode> nodes(List<?> entities) {
        return entities.stream()
                .map(entity -> objectMapper.<ObjectNode>valueToTree(entity))
                .toList();
    }

    /**
     * 给每条记录注入**稳定记录 ID**（R37）。
     *
     * <p>形如 {@code web:jobDescriptions:17}，集合名即导出集合名；嵌套记录带父前缀
     * （{@code web:resumes:5:versions:9}），保证不同来源、不同层级的主键不会互相撞号 ——
     * 桌面端正是靠它判断「这条是不是上次导入的那条」。
     */
    void injectStableIds(Map<String, Object> payload) {
        for (String collection : collections) {
            for (ObjectNode record : objectNodes(payload.get(collection))) {
                JsonNode id = record.get("id");
                if (id == null || id.isNull()) {
                    continue;
                }
                record.put("stableId", source + ":" + collection + ":" + id.asText());
                if ("resumes".equals(collection)) {
                    injectChildStableIds(record, "versions");
                } else if ("applications".equals(collection)) {
                    injectChildStableIds(record, "statusHistory");
                }
            }
        }
    }

    /**
     * 包的清单（R37）：格式版本、来源、记录计数，以及每类记录的稳定 ID 列表。
     * 桌面端导入前先校验它 —— 没有清单的包无法判断「重复导入」与「同 ID 冲突」。
     */
    Map<String, Object> manifest(Map<String, Object> payload) {
        Map<String, Object> collectionsNode = new LinkedHashMap<>();
        int total = 0;
        for (String collection : collections) {
            Object value = payload.get(collection);
            List<?> list = value instanceof List<?> l ? l : List.of();
            List<String> stableIds = new ArrayList<>();
            for (ObjectNode record : objectNodes(value)) {
                JsonNode stableId = record.get("stableId");
                if (stableId != null && !stableId.isNull()) {
                    stableIds.add(stableId.asText());
                }
            }
            collectionsNode.put(collection, Map.of("count", list.size(), "stableIds", stableIds));
            total += list.size();
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("packageId", source + "-export-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        manifest.put("source", source);
        manifest.put("recordCount", total);
        manifest.put("collections", collectionsNode);
        return manifest;
    }

    private void injectChildStableIds(ObjectNode parent, String childCollection) {
        String parentStableId = parent.path("stableId").asText();
        for (ObjectNode child : objectNodes(parent.get(childCollection))) {
            JsonNode id = child.get("id");
            if (id != null && !id.isNull()) {
                child.put("stableId", parentStableId + ":" + childCollection + ":" + id.asText());
            }
        }
    }

    /** 集合 → 对象节点（**原节点**，不是副本 —— 注入必须改到负载本身上）。 */
    private List<ObjectNode> objectNodes(Object value) {
        List<ObjectNode> nodes = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof ObjectNode node) {
                    nodes.add(node);
                }
            }
        }
        return nodes;
    }

    /** JSON 数组节点 → 对象节点（子集合专用，同样是原节点）。 */
    private List<ObjectNode> objectNodes(JsonNode array) {
        List<ObjectNode> nodes = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode element : array) {
                if (element instanceof ObjectNode node) {
                    nodes.add(node);
                }
            }
        }
        return nodes;
    }
}
