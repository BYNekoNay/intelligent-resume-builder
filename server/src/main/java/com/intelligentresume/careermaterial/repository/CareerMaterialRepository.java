package com.intelligentresume.careermaterial.repository;

import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.dto.CareerMaterialListRow;
import com.intelligentresume.careermaterial.dto.CareerMaterialTypeCount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 职业资料仓储。
 *
 * <p>要点：
 * <ul>
 *   <li>隔离维度只有 userId：除 {@code findById}（仅测试/内部使用）外，所有查询都把
 *       {@code user_id} 写进 where，新增方法也必须带；</li>
 *   <li>软删由实体上的 {@code @SQLRestriction} 统一过滤，本接口不需要也不应再写
 *       {@code deleted_at is null}——重复写会让 JPQL 与原生查询口径分叉；</li>
 *   <li>返回实体的方法会带出 MEDIUMTEXT 原文，列表/摘要类场景请用投影方法；</li>
 *   <li>需要在 AI 生成等链路保证"同毫秒写入也稳定"时，只能用
 *       {@code findByUserIdOrderByUpdatedAtDescIdDesc}，单键排序不够。</li>
 * </ul>
 */
public interface CareerMaterialRepository extends JpaRepository<CareerMaterial, Long> {

    Optional<CareerMaterial> findByIdAndUserId(Long id, Long userId);

    /**
     * 用户全部资料按 (updated_at, id) 双键降序。career_material.updated_at 为毫秒
     * 精度，批量确认可在同一毫秒写入多条；MaterialSelector 对 normal 的截断按传入
     * 顺序进行，单键排序在并列时会让「被丢弃的素材集合」run-to-run 变化，必须由
     * 自增 id 兜底。AI 生成/选材/导出中需要确定性的读路径均应使用本方法。
     */
    List<CareerMaterial> findByUserIdOrderByUpdatedAtDescIdDesc(Long userId);

    /**
     * 列表读模型（ideation #1）：只投影摘要列 + contentJson + 「原文非空」标志，
     * 不传输 MEDIUMTEXT sourceText；类型过滤下推到 SQL，不再内存过滤。
     */
    @Query("""
            select new com.intelligentresume.careermaterial.dto.CareerMaterialListRow(
                material.id, material.materialType, material.title, material.usagePreference,
                material.updatedAt, material.contentJson,
                case when trim(coalesce(material.sourceText, '')) <> '' then true else false end)
            from CareerMaterial material
            where material.userId = :userId
              and (:materialType is null or material.materialType = :materialType)
            order by material.updatedAt desc
            """)
    List<CareerMaterialListRow> findListRowsByUserId(
            @Param("userId") Long userId,
            @Param("materialType") MaterialType materialType);

    /**
     * 搜索：三列包含匹配（标题 / 原文 / contentJson 文本投影），全部 lower 后比较。
     *
     * <p>key 点：
     * <ul>
     *   <li>{@code escape '\\'} 与调用方 {@code normalizeQuery} 的转义必须成对，
     *       单边改动会让含 {@code %} / {@code _} 的搜索词行为突变；</li>
     *   <li>{@code contentJsonText} 是 {@code @Formula} 投影，依赖 JSON→字符串隐式转换，
     *       换成其他数据库或显式 cast 时这里要跟着改；</li>
     *   <li>三个 like 均无索引可用，属全表包含扫描，靠调用方的长度/分页上限控成本。</li>
     * </ul>
     */
    @Query("""
            select material from CareerMaterial material
            where material.userId = :userId
              and (:materialType is null or material.materialType = :materialType)
              and (:usagePreference is null or material.usagePreference = :usagePreference)
              and (:query is null
                   or lower(material.title) like concat('%', lower(:query), '%') escape '\\'
                   or lower(coalesce(material.sourceText, '')) like concat('%', lower(:query), '%') escape '\\'
                   or lower(material.contentJsonText) like concat('%', lower(:query), '%') escape '\\')
            """)
    Page<CareerMaterial> search(
            @Param("userId") Long userId,
            @Param("materialType") MaterialType materialType,
            @Param("usagePreference") UsagePreference usagePreference,
            @Param("query") String query,
            Pageable pageable);

    /**
     * 按类型分组计数（不忽略空类型，故无资料的枚举值不会出现）。
     * 只用于筛选标签的总数，不随搜索条件变化。
     */
    @Query("""
            select new com.intelligentresume.careermaterial.dto.CareerMaterialTypeCount(
                material.materialType, count(material))
            from CareerMaterial material
            where material.userId = :userId
            group by material.materialType
            """)
    List<CareerMaterialTypeCount> countByType(@Param("userId") Long userId);

    /** 当前用户的资料条数（软删已由 @SQLRestriction 排除）。 */
    long countByUserId(Long userId);
}
