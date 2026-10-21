package com.intelligentresume.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;

import java.io.IOException;

/**
 * 桌面端（H2）专用的**宽容 JSON 读**：兼容历史上以字符串形式写进 JSON 列的行。
 *
 * <p><b>为什么需要它</b>：H2 的 JSON 列对**参数绑定方式**敏感（实测字节形态）：
 * <ul>
 *   <li>Hibernate 自己写 → JSON 对象：{@code byte[29]={"taskType":"JOB_GENERATION"}}</li>
 *   <li>任何 {@code setString}（JDBC / 旧版导入器）→ JSON <b>字符串标量</b>（双编码）：
 *       {@code byte[35]="{\"taskType\":\"JOB_GENERATION\"}"}</li>
 * </ul>
 * 读回时 Hibernate 的 {@code JacksonJsonFormatMapper.fromString} 拿到「内容是 JSON 文本的
 * JSON 字符串」，转成 {@code Map} 时抛
 * {@code IllegalArgumentException: Could not deserialize string to java type: JsonJavaType(Map)}，
 * 于是整条 AI 任务链路 / 简历版本读取在桌面端不可用。MySQL 会把文本规范化成对象，所以只有
 * H2（桌面端）踩到。
 *
 * <p><b>为什么不能继承 {@code JacksonJsonFormatMapper}</b>：它是 {@code final}，只能直接实现
 * {@link FormatMapper}。
 *
 * <p><b>宽容到什么程度（刻意只多做一件事）</b>：先按目标类型正常反序列化；**只有**失败且
 * 解析出来的节点是文本节点时，才把该文本再解一层（且只解一层）。因此：
 * <ul>
 *   <li>MySQL（永不产生双编码）与 Hibernate 自己写的行 → 行为与默认 mapper 完全一致；</li>
 *   <li>非 JSON 文本（如 {@code not-json}）→ 依然抛错，不会「什么都放过」
 *       （由 {@code JsonColumnH2ReadbackIT} 的反证用例钉住）；</li>
 *   <li>已经写坏的历史行 → 直接读得出来，不需要数据迁移、不需要改 schema。</li>
 * </ul>
 *
 * <p><b>只配在 desktop profile</b>（见 {@code application-desktop.yml}）：生产 MySQL 路径
 * 一个字节都不改，避免把真实的数据形态问题在线上静默吸收掉。
 *
 * <p>配套的写侧修复在 {@code DesktopImportService}（导入时对 JSON 列用
 * {@code ? FORMAT JSON} 绑定，让新行就是规范对象），本文档类只负责兼容存量行。
 */
public final class LenientJsonFormatMapper implements FormatMapper {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public <T> T fromString(CharSequence charSequence, JavaType<T> javaType, WrapperOptions options) {
        String text = charSequence.toString();
        try {
            return mapper.readerFor(javaType.getJavaTypeClass()).readValue(text);
        } catch (IOException | IllegalArgumentException directFailure) {
            JsonNode node = readTreeOrNull(text);
            if (node != null && node.isTextual()) {
                // 双编码：列里存的是「JSON 文本的 JSON 字符串」，剥一层再按目标类型解析。
                try {
                    return mapper.readerFor(javaType.getJavaTypeClass()).readValue(node.textValue());
                } catch (IOException nested) {
                    throw new IllegalArgumentException("JSON 列内容为双层编码，且内层也无法反序列化为 "
                            + javaType.getJavaTypeClass() + "：" + abbreviate(node.textValue()), directFailure);
                }
            }
            throw new IllegalArgumentException("JSON 列内容无法反序列化为 " + javaType.getJavaTypeClass()
                    + "：" + abbreviate(text), directFailure);
        }
    }

    @Override
    public <T> String toString(T value, JavaType<T> javaType, WrapperOptions options) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException failure) {
            throw new IllegalArgumentException("无法把 " + javaType.getJavaTypeClass() + " 序列化为 JSON", failure);
        }
    }

    private JsonNode readTreeOrNull(String text) {
        try {
            return mapper.readTree(text);
        } catch (IOException notJson) {
            return null;
        }
    }

    /** 异常信息里只带前 200 字符，避免把整份快照（可达 64KB）打进日志。 */
    private String abbreviate(String text) {
        String flattened = text.replace('\n', ' ').replace('\r', ' ');
        return flattened.length() <= 200 ? flattened : flattened.substring(0, 200) + "…";
    }
}
