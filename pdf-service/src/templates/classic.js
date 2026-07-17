// 固定模板只接受结构化 JSON Resume 数据，不加载远程图片、字体或样式。

export const TEMPLATE_CODES = new Set(['classic', 'modern', 'minimal'])

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

function templateStyles(templateCode) {
  const variants = {
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
  }
  return variants[templateCode]
}

export function renderResumeHtml(templateCode, payload) {
  if (!TEMPLATE_CODES.has(templateCode)) throw new Error('不支持的简历模板')
  const resume = payload?.resumeJson ?? {}
  const basics = resume.basics ?? {}
  const arrays = (key) => Array.isArray(resume[key]) ? resume[key] : []
  const work = arrays('work')
  const skills = arrays('skills')
  const projects = arrays('projects')
  const education = arrays('education')
  const certificates = arrays('certificates')
  const languages = arrays('languages')
  const text = (value) => escapeHtml(value ?? '')
  const list = (items, render) => items.map(render).join('')
  const section = (title, content) => content ? `<section><h2>${title}</h2>${content}</section>` : ''
  const highlights = (item) => Array.isArray(item.highlights) && item.highlights.length
    ? `<ul>${list(item.highlights, (point) => `<li>${text(point?.text ?? point?.value ?? point)}</li>`)}</ul>`
    : ''
  const dates = (item) => [item.startDate, item.endDate].filter(Boolean).map(text).join(' — ')

  const workHtml = list(work, (item) => `<article class="entry"><strong>${text(item.company || item.name || '公司名称')}</strong><span>${text(item.position || item.role)}</span><small>${dates(item)}</small>${item.description ? `<p>${text(item.description)}</p>` : ''}${highlights(item)}</article>`)
  const skillsHtml = list(skills, (item) => `<span>${text(item?.name ?? item?.keyword ?? item)}</span>`)
  const projectsHtml = list(projects, (item) => `<article class="entry"><strong>${text(item.name || '项目名称')}</strong><span>${text(item.role || item.position)}</span>${item.description ? `<p>${text(item.description)}</p>` : ''}${highlights(item)}</article>`)
  const educationHtml = list(education, (item) => `<article class="entry"><strong>${text(item.school || item.name)}</strong><span>${[item.degree, item.major || item.area].filter(Boolean).map(text).join(' · ')}</span><small>${dates(item)}</small></article>`)
  const certificatesHtml = list(certificates, (item) => `<article class="entry"><strong>${text(item.name || '证书名称')}</strong><span>${text(item.issuer)}</span><small>${text(item.date)}</small></article>`)
  const languagesHtml = list(languages, (item) => `<span>${[item.name || item.language, item.level || item.fluency].filter(Boolean).map(text).join(' · ')}</span>`)
  const location = typeof basics.location === 'string' ? basics.location : basics.location?.city
  const contact = [basics.phone, basics.email, location].filter(Boolean).map(text).join(' · ')

  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>resume</title><style>
    @page{size:A4;margin:0}*{box-sizing:border-box}body{margin:0;min-height:297mm;padding:16mm;font-size:10pt;line-height:1.65;-webkit-print-color-adjust:exact;print-color-adjust:exact}
    .paper-header{padding-bottom:5mm;margin-bottom:6mm}.paper-header h1{margin:0;font:700 24pt "Microsoft YaHei",sans-serif;letter-spacing:.04em}.paper-header .role{margin:1.5mm 0;font:700 10.5pt "Microsoft YaHei",sans-serif}.contact{font:9pt "Microsoft YaHei",sans-serif}
    section{margin:0 0 6mm}h2{display:flex;align-items:center;gap:3mm;margin:0 0 3mm;font:700 10pt "Microsoft YaHei",sans-serif;letter-spacing:.12em}h2::after{content:"";width:7mm;height:1px}
    p,li{font-size:9.5pt}.summary{margin:0;white-space:pre-wrap}.entry{display:grid;grid-template-columns:1fr auto;gap:1mm 4mm;margin:0 0 3.5mm;break-inside:avoid}.entry strong{grid-column:1;grid-row:1;font:700 10.5pt "Microsoft YaHei",sans-serif}.entry>span{grid-column:1;grid-row:2}.entry>small{grid-column:2;grid-row:1/3;text-align:right}.entry span,.entry small{font:9pt "Microsoft YaHei",sans-serif}.entry p,.entry ul{grid-column:1/-1;margin:1mm 0 0}.entry ul{padding-left:5mm}.chips{display:flex;flex-wrap:wrap;gap:2mm}.chips span{padding:1mm 2.5mm;font:9pt "Microsoft YaHei",sans-serif}
    ${templateStyles(templateCode)}
  </style></head><body>
    <header class="paper-header"><h1>${text(basics.name || '你的姓名')}</h1><p class="role">${text(basics.title || basics.position || basics.label || '目标岗位')}</p><div class="contact">${contact}</div></header>
    ${section('个人概要', basics.summary ? `<p class="summary">${text(basics.summary)}</p>` : '')}
    ${section('工作经历', workHtml)}
    ${section('专业技能', skillsHtml ? `<div class="chips">${skillsHtml}</div>` : '')}
    ${section('项目经历', projectsHtml)}
    ${section('教育经历', educationHtml)}
    ${section('专业证书', certificatesHtml)}
    ${section('语言能力', languagesHtml ? `<div class="chips">${languagesHtml}</div>` : '')}
  </body></html>`
}

export function renderClassicHtml(payload) {
  return renderResumeHtml('classic', payload)
}

export default { TEMPLATE_CODES, escapeHtml, rejectExternalUrl, renderClassicHtml, renderResumeHtml }
