# ATS 友好简历模板调研

调研日期：2026-07-27。目标是为编辑器增加模板时提供可验证的结构依据。这里的“通过率高”不能从公开模板页推导为真实招聘通过率；以下只记录产品方或招聘软件方公开说明的可核验事实，并把设计建议标为产品推论。

## 一手资料中的可验证事实

### 简历解析器关注可抽取文本

[Indeed for Employers：An Employer's Guide to Resume Parsing](https://www.indeed.com/hire/c/info/resume-parsing)（官方招聘方资料）说明：

- 解析程序通常接收 PDF 和 Word 文档，并将文档信息上传、排序和存储；
- 关键词解析会扫描技能、证书和学位等关键词；语法解析会识别词组和词串；统计解析会使用数值和模型；
- 解析结果会被用于按技能、证书、学历和工作经历筛选。

这证明了“文本是否能被稳定抽取、以及关键词所在的语义上下文”是 ATS 友好性的核心约束，但该页没有承诺任何具体模板的招聘通过率。

### Novorésumé 对 ATS 友好模板的公开定义

[Novorésumé：16+ Free Resume Templates](https://novoresume.com/resume-templates)（官方模板页）直接把其模板描述为 ATS-friendly，并给出实现描述：

- clean layout（清晰布局）；
- simple fonts（简单字体）；
- clear headings（明确标题）；
- proper keyword placement（合适的关键词位置）；
- avoid complex graphics（避免复杂图形）。

同一页面还按求职场景提供 Skill-Based、Minimalist、Hybrid、Traditional、General、IT、Tech、Combined 等类型，并说明 Minimalist 面向偏保守行业，Hybrid 同时强调技能和经历，Traditional 对各区块给予均衡权重，Skill-Based 适合转行者或应届生。以上是产品方的定位说明，不是独立的招聘成功率实验。

### FlowCV 的官方模板分类

[FlowCV：100+ Free Resume & CV Templates](https://flowcv.com/resume-templates)（官方模板目录）按 Simple、Modern、Creative、Photo、Compact、First Job 分类，并对分类给出适用描述：Simple 强调清晰、可读和无干扰；Modern 使用当代设计元素；Compact 在保持结构清晰的前提下容纳更多内容；First Job 突出技能、成就、教育和动机。该目录同时列出 Finance、Harvard、Banking、McKinsey、Law、Academic、Scientist、Software Developer 等示例场景。

这说明模板选择可以按“结构/密度/行业场景”组织，而不只是按颜色命名；目录本身没有公开 ATS 通过率数据。

### Reactive Resume 的能力边界

[Reactive Resume 官方 GitHub README](https://github.com/AmruthPillai/Reactive-Resume)列出实时预览、区块拖拽排序、模板、颜色/字体/间距设置、多语言和 JSON 导出。它证明“内容结构与外观设置分开、预览即时反馈、区块可排序”是成熟开源产品的明确能力组合；README 没有宣称招聘通过率。

## 可落地的模板矩阵

建议不要一次做大量仅颜色不同的模板，而是先做结构差异明确、风险可解释的 6 套：

| 模板 | 结构与视觉 | 适用场景 | ATS 风险策略 |
| --- | --- | --- | --- |
| 经典单栏 | 反向时间顺序，左对齐，清晰标题，单一强调线 | 通用、金融、行政、传统行业 | 默认模板；不使用侧栏、图标或照片 |
| 现代单栏 | 单栏主体 + 更明显的标题层级和少量色彩 | 软件、产品、数据、运营 | 颜色只用于标题/分隔线，正文保持可抽取文本 |
| 紧凑单栏 | 更小但可读的间距，优先保留经历和成果 | 经历较多、希望控制页数 | 不压缩字号到不可读；页数变化实时提示 |
| 技能混合 | 顶部技能摘要，随后按反向时间列出经历 | 转行、应届、技能导向职位 | 技能仍使用文本标签和标准标题，不用评分条/圆点替代文字 |
| 学术/项目 | 教育、项目、研究/证书靠前，经历保持时间线 | 应届生、科研、技术项目 | 区块标题固定，项目成果使用可搜索文本 |
| 创意受控 | 单栏主体，允许少量色带/字体变化，不放照片和复杂图表 | 设计、营销、内容岗位 | 明确标注“ATS 兼容优先”；复杂视觉仅作为可选非默认模板 |

“通过率高”应在产品中改写为可验证的提示，例如“ATS 兼容优先”“适合传统行业”，不要显示未经实验支持的百分比或保证。

## 设计约束（产品推论）

结合 Indeed 的解析机制和 Novorésumé 的公开定义，模板实现应遵守：

1. **单栏作为默认安全基线。** 多栏、侧栏和自由定位会增加阅读顺序不确定性；在没有针对目标 ATS 的解析测试时，不把它们作为默认模板。
2. **使用稳定的标准标题。** “工作经历、项目经历、教育经历、技能、证书、语言、奖项”应生成真实文本标题，不能只用图标、装饰线或图片。标题应保持固定顺序并能被关键词解析识别。
3. **字体优先可读和可嵌入。** 提供系统常见 sans/serif 字体，避免仅依赖装饰字体；字号和行距设置需设可读性下限。字体选项的变化不应改变字段语义或隐藏文本。
4. **颜色只表达层级。** 允许一到两种强调色，用于标题、细线和链接；正文保持高对比度。颜色不是 ATS 证据，不应作为“通过率”卖点。
5. **图形不可承载关键信息。** 不用进度条、星级、饼图、图标或照片代替技能、联系方式、语言水平等文本；图形若存在，只能是装饰且不影响文本顺序。
6. **关键词写在真实字段中。** 技能、证书、工具和成果写入可复制文本；不要在画布中只展示视觉标签而把值放入 CSS/图片。AI 只能润色目标字段，不自动添加未确认事实。
7. **导出后仍要可抽取。** 用 PDF/Word 导出后做文本复制和顺序检查；预览中的页数/溢出提示不能替代导出验收。

## 对当前产品的直接建议

- 模板选择器按“推荐 / 单栏 / 技能导向 / 紧凑 / 创意受控”分组，卡片同时展示结构缩略图、适用场景和 ATS 风险提示。
- 将现有经典模板作为默认“ATS 兼容优先”，现代模板作为低风险单栏变体；新增模板先复用同一 `resumeJson` 结构，不为视觉效果新增字段。
- 每套模板增加稳定的 `templateId` 和 `atsProfile` 元数据（仅前端展示/测试用途），例如 `single-column-safe`、`skill-hybrid`，不要上传或改变后端 schema。
- 增加模板回归检查：对相同简历 JSON 逐套渲染，断言姓名、联系方式、区块标题、公司/学校、技能等文本在 DOM 中存在且顺序正确；再检查 PDF/打印文本抽取。
- 在模板选择器旁显示“无真实通过率保证”的说明，把产品承诺限制为“结构清晰、ATS 兼容优先”。

## 证据边界

公开资料没有提供跨 ATS 厂商、跨行业和跨模板的真实通过率对照实验。具体 ATS 对 PDF、字体、表格、列布局和图形的处理可能不同，因此应把“ATS 友好”当作设计目标和可测试属性，而不是结果保证。最终验收应使用目标招聘渠道的实际导出文件和文本抽取测试。

## 来源

- Indeed for Employers, “An Employer's Guide to Resume Parsing”, https://www.indeed.com/hire/c/info/resume-parsing ，访问于 2026-07-27。
- Novorésumé, “16+ Free Resume Templates”, https://novoresume.com/resume-templates ，访问于 2026-07-27。
- FlowCV, “100+ Free Resume & CV Templates”, https://flowcv.com/resume-templates ，访问于 2026-07-27。
- Reactive Resume, 官方 GitHub README, https://github.com/AmruthPillai/Reactive-Resume ，访问于 2026-07-27。
