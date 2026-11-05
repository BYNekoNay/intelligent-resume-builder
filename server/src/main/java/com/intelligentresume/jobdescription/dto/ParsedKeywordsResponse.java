package com.intelligentresume.jobdescription.dto;

import java.util.List;

/**
 * 确定性关键词解析结果。
 *
 * @param role         第一行非空短行(可选,>60 字符截断)
 * @param keywords     词典命中(去重,保持首次出现顺序)
 * @param requirements 经验年限、教育等提取结果
 *
 * <p>要点：
 * <ul>
 *   <li>{@code role} 用 null 表示"抽不到"，不是空串；短文本整体解析时会三项全空；</li>
 *   <li>{@code keywords} 的实际顺序是<b>词典声明顺序</b>（代码遍历词典命中即收集），
 *       与上面 @param 里的"首次出现顺序"不一致，以代码为准；</li>
 *   <li>{@code requirements} 先放正则命中的经验年限、再追加教育关键词，两类共用去重集合；</li>
 *   <li>两个列表在空结果时不可变（{@code List.of()}），调用方不要尝试原地修改。</li>
 * </ul>
 */
public record ParsedKeywordsResponse(
        String role,
        List<String> keywords,
        List<String> requirements
) {}
