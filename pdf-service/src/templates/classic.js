// 固定模板只接受结构化 JSON Resume 数据，不加载远程图片、字体或样式。

export function escapeHtml(value) {
  if (value === null || value === undefined) return ''
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

export function rejectExternalUrl(value) {
  if (typeof value !== 'string') return false
  return /^(?:[a-z]+:|\/\/)/i.test(value.trim())
}

const TEMPLATE_STYLES = {
    classic: `
      body{color:#18212f;background:#fbfcfe;font-family:Georgia,"Songti SC",serif}
      .paper-header{border-bottom:3px solid #283c59}
      .paper-header h1{color:#14263e}.paper-header .role{color:#40536c}.contact{color:#62738a}
      h2{color:#203b5c}h2::after{background:#ef9651}.meta{color:#53657b}
      .chips span{border:1px solid #d5dfe9;border-radius:999px;color:#274769}
    `,
    modern: `
      body{color:#1d2f3f;background:#f7fafc;font-family:Georgia,"Songti SC",serif}
      .paper-header{margin:-16mm -16mm 8mm;padding:11mm 16mm 7mm;border:0;background:#173f5f}
      .paper-header h1,.paper-header .role,.paper-header .contact{color:#fff}.paper-header .contact{opacity:.78}
      section{padding-left:5mm;border-left:3px solid #4fa3c7}h2{color:#173f5f}h2::after{background:#4fa3c7}.meta{color:#53657b}
      .chips span{border:0;border-radius:4px;color:#173f5f;background:#dcecf3}
    `,
    minimal: `
      body{color:#242424;background:#fff;font-family:"Songti SC",Georgia,serif}
      .paper-header{padding-bottom:7mm;border-bottom:1px solid #292929;text-align:center}
      .paper-header h1{color:#171717;font-weight:500;letter-spacing:.12em}.paper-header .role{color:#333;font-family:"Microsoft YaHei",sans-serif;font-size:9.5pt;font-weight:500;letter-spacing:.16em}.contact{color:#666}
      h2{color:#222;font-weight:500;letter-spacing:.18em}h2::after{width:10mm;background:#222}.entry strong{font-family:"Songti SC",Georgia,serif;font-weight:600}.meta{color:#666}
      .chips span{padding:1mm .5mm;border:0;border-bottom:1px solid #aaa;border-radius:0;color:#333}
    `,
    ats: `
      body{color:#111;background:#fff;font-family:Arial,"Microsoft YaHei",sans-serif}
      .paper-header{border-bottom:1px solid #111}.paper-header h1,.paper-header .role,h2{color:#111}.contact,.meta{color:#333}
      h2::after{background:#111}.chips span{padding:0;border:0;border-radius:0;color:#111}.chips span:not(:last-child)::after{content:",";margin-right:2mm}
    `,
    executive: `
      body{color:#182432;background:#fdfdfc;font-family:Georgia,"Songti SC",serif}
      .paper-header{padding-left:6mm;border-left:5px solid #9b7b3f;border-bottom:1px solid #d8ceb9}.paper-header h1,h2{color:#182432}.paper-header .role,.contact,.meta{color:#625c50}
      h2::after{background:#9b7b3f}.chips span{border:1px solid #cfc4ae;border-radius:2px;color:#493f2c}
    `,
    compact: `
      body{color:#17212b;background:#fff;font-family:"Microsoft YaHei",Arial,sans-serif}
      .paper-header{padding-bottom:3mm;margin-bottom:4mm;border-bottom:2px solid #2d6a78}section{margin-bottom:calc(var(--resume-section-gap) * .66)}
      .paper-header h1,h2{color:#183f49}.paper-header .role,.contact,.meta{color:#49646b}h2::after{background:#4f8d99}.entry{margin-bottom:calc(var(--resume-entry-gap) * .66)}
      .chips span{padding:.5mm 1.5mm;border:1px solid #bcd0d4;border-radius:2px;color:#214f59}
    `,
    academic: `
      body{color:#202020;background:#fff;font-family:"Times New Roman","Songti SC",serif}
      .paper-header{border-bottom:2px double #333}.paper-header h1,h2{color:#111}.paper-header .role,.contact,.meta{color:#444}
      h2{font-variant:small-caps}h2::after{background:#555}.chips span{padding:0;border:0;border-radius:0;color:#222}.chips span:not(:last-child)::after{content:" · ";white-space:pre}
    `,
}

export const TEMPLATE_CODES = new Set(Object.keys(TEMPLATE_STYLES))

function templateStyles(templateCode) {
  return TEMPLATE_STYLES[templateCode]
}

function boundedNumber(value, fallback, min, max) {
  const number = Number(value)
  return Number.isFinite(number) ? Math.min(max, Math.max(min, number)) : fallback
}

function fontStack(code) {
  const families = {
    sans: '"Microsoft YaHei",Arial,sans-serif',
    songti: '"Songti SC",SimSun,serif',
    serif: 'Georgia,"Times New Roman",serif',
    mono: '"Cascadia Mono","Courier New",monospace',
  }
  return families[code] ?? families.sans
}

function layoutStyles(layout) {
  const bodyFontSize = boundedNumber(layout?.bodyFontSize, 13, 11, 16) * 0.75
  const headingFontSize = boundedNumber(layout?.headingFontSize, 13, 11, 18) * 0.75
  const nameFontSize = headingFontSize * 24 / 9.75
  const roleFontSize = headingFontSize * 10.5 / 9.75
  const lineHeight = boundedNumber(layout?.lineHeight, 1.65, 1.3, 2)
  const sectionSpacing = boundedNumber(layout?.sectionSpacing, 20, 10, 32) * 0.75
  const entrySpacing = boundedNumber(layout?.entrySpacing, 12, 6, 22) * 0.75
  const pagePadding = boundedNumber(layout?.pagePadding, 58, 32, 80) * 0.75

  return `:root{--resume-font-family:${fontStack(layout?.fontFamily)};--resume-body-size:${bodyFontSize}pt;--resume-heading-size:${headingFontSize}pt;--resume-name-size:${nameFontSize}pt;--resume-role-size:${roleFontSize}pt;--resume-line-height:${lineHeight};--resume-section-gap:${sectionSpacing}pt;--resume-entry-gap:${entrySpacing}pt;--paper-pad:${pagePadding}pt}`
}

export function renderResumeHtml(templateCode, payload) {
  if (!TEMPLATE_CODES.has(templateCode)) throw new Error('不支持的简历模板')
  const resume = payload?.resumeJson ?? payload ?? {}
  const layout = resume.layout ?? {}
  const basics = resume.basics ?? {}
  const objective = resume.objective ?? {}
  const arrays = (key) => Array.isArray(resume[key]) ? resume[key] : []
  const links = arrays('links')
  const work = arrays('work')
  const volunteering = arrays('volunteering')
  const skills = arrays('skills')
  const projects = arrays('projects')
  const education = arrays('education')
  const courses = arrays('courses')
  const certificates = arrays('certificates')
  const publications = arrays('publications')
  const awards = arrays('awards')
  const languages = arrays('languages')
  const customSections = arrays('customSections')
  const text = (value) => escapeHtml(value ?? '')
  const list = (items, render) => items.map(render).join('')
  const section = (title, content) => content ? `<section><h2>${text(title)}</h2>${content}</section>` : ''
  const highlights = (item) => Array.isArray(item.highlights) && item.highlights.length
    ? `<ul>${list(item.highlights, (point) => `<li>${text(point?.text ?? point?.value ?? point)}</li>`)}</ul>`
    : ''
  const dates = (item) => [item.startDate, item.endDate].filter(Boolean).map(text).join(' — ')
  const description = (item) => item.description ? `<p>${text(item.description)}</p>` : ''
  const entry = (item, title, subtitle = '', date = dates(item)) => `<article class="entry"><strong>${text(title)}</strong><span>${text(subtitle)}</span><small>${date}</small>${description(item)}${highlights(item)}</article>`

  const objectiveHtml = objective.summary
    ? `<p class="meta">${[objective.targetRole, objective.targetIndustry, objective.location].filter(Boolean).map(text).join(' · ')}</p><p class="summary">${text(objective.summary)}</p>`
    : ''
  const linksHtml = list(links, (item) => entry(item, item.label || item.name, item.url || '', ''))
  const workHtml = list(work, (item) => entry(item, item.company || item.name || '公司名称', item.position || item.role))
  const volunteeringHtml = list(volunteering, (item) => entry(item, item.organization || item.name, item.role || item.position))
  const skillsHtml = list(skills, (item) => `<span>${text(item?.name ?? item?.keyword ?? item)}</span>`)
  const projectsHtml = list(projects, (item) => entry(item, item.name || '项目名称', item.role || item.position))
  const educationHtml = list(education, (item) => entry(item, item.school || item.name, [item.degree, item.major || item.area].filter(Boolean).join(' · ')))
  const coursesHtml = list(courses, (item) => entry(item, item.name, item.provider || '', text(item.date)))
  const certificatesHtml = list(certificates, (item) => entry(item, item.name || '证书名称', item.issuer || '', text(item.date)))
  const publicationsHtml = list(publications, (item) => entry(item, item.title || item.name, item.publisher || item.url || '', text(item.date)))
  const awardsHtml = list(awards, (item) => entry(item, item.name || item.title, item.issuer || item.organization || '', text(item.date)))
  const languagesHtml = list(languages, (item) => `<span>${[item.name || item.language, item.level || item.fluency].filter(Boolean).map(text).join(' · ')}</span>`)
  const customSectionsHtml = list(customSections, (group) => section(group.title || '自定义模块', list(Array.isArray(group.entries) ? group.entries : [], (item) => entry(item, item.name, [item.organization, item.role].filter(Boolean).join(' · ')))))
  const sectionHtml = {
    objective: section('求职目标', objectiveHtml),
    links: section('个人链接', linksHtml),
    work: section('工作经历', workHtml),
    volunteering: section('实习 / 志愿经历', volunteeringHtml),
    skills: section('专业技能', skillsHtml ? `<div class="chips">${skillsHtml}</div>` : ''),
    projects: section('项目经历', projectsHtml),
    education: section('教育经历', educationHtml),
    courses: section('培训课程', coursesHtml),
    certificates: section('专业证书', certificatesHtml),
    publications: section('研究成果', publicationsHtml),
    awards: section('奖项荣誉', awardsHtml),
    languages: section('语言能力', languagesHtml ? `<div class="chips">${languagesHtml}</div>` : ''),
    customSections: customSectionsHtml,
  }
  const defaultOrder = Object.keys(sectionHtml)
  const savedOrder = Array.isArray(layout.sectionOrder) ? layout.sectionOrder.filter((key) => defaultOrder.includes(key)) : []
  const orderedSections = [...new Set(savedOrder), ...defaultOrder.filter((key) => !savedOrder.includes(key))]
    .map((key) => sectionHtml[key]).join('')
  const location = typeof basics.location === 'string' ? basics.location : basics.location?.city
  const contact = [basics.phone, basics.email, location].filter(Boolean).map(text).join(' · ')

  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>resume</title><style>
    ${layoutStyles(layout)}
    @page{size:A4;margin:0}*{box-sizing:border-box}body{margin:0;min-height:297mm;padding:var(--paper-pad);font-size:10pt;line-height:var(--resume-line-height);-webkit-print-color-adjust:exact;print-color-adjust:exact}
    .paper-header{padding-bottom:5mm;margin-bottom:6mm}.paper-header h1{margin:0;font:700 var(--resume-name-size) "Microsoft YaHei",sans-serif;letter-spacing:.04em}.paper-header .role{margin:1.5mm 0;font:700 var(--resume-role-size) "Microsoft YaHei",sans-serif}.contact{font:9pt "Microsoft YaHei",sans-serif}
    section{margin:0 0 var(--resume-section-gap)}h2{display:flex;align-items:center;gap:3mm;margin:0 0 3mm;font:700 var(--resume-heading-size) "Microsoft YaHei",sans-serif;letter-spacing:.12em}h2::after{content:"";width:7mm;height:1px}
    p,li{font-size:var(--resume-body-size)}.summary{margin:0;white-space:pre-wrap}.entry{display:grid;grid-template-columns:1fr auto;gap:1mm 4mm;margin:0 0 var(--resume-entry-gap);break-inside:avoid}.entry strong{grid-column:1;grid-row:1;font:700 calc(var(--resume-body-size) + 0.75pt) "Microsoft YaHei",sans-serif}.entry>span{grid-column:1;grid-row:2}.entry>small{grid-column:2;grid-row:1/3;text-align:right}.entry span,.entry small{font:calc(var(--resume-body-size) - 0.5pt) "Microsoft YaHei",sans-serif}.entry p,.entry ul{grid-column:1/-1;margin:1mm 0 0}.entry ul{padding-left:5mm}.chips{display:flex;flex-wrap:wrap;gap:2mm}.chips span{padding:1mm 2.5mm;font:calc(var(--resume-body-size) - 0.5pt) "Microsoft YaHei",sans-serif}
    ${templateStyles(templateCode)}
    body,.paper-header h1,.paper-header .role,.contact,h2,p,li,.entry strong,.entry span,.entry small,.chips span{font-family:var(--resume-font-family)}
  </style></head><body>
    <header class="paper-header"><h1>${text(basics.name || '你的姓名')}</h1><p class="role">${text(basics.title || basics.position || basics.label || '目标岗位')}</p><div class="contact">${contact}</div></header>
    ${section('个人概要', basics.summary ? `<p class="summary">${text(basics.summary)}</p>` : '')}
    ${orderedSections}
  </body></html>`
}

export function renderClassicHtml(payload) {
  return renderResumeHtml('classic', payload)
}

export default { TEMPLATE_CODES, escapeHtml, rejectExternalUrl, renderClassicHtml, renderResumeHtml }
