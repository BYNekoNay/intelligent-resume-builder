import test from 'node:test'
import assert from 'node:assert/strict'
import { renderResumeHtml, TEMPLATE_CODES } from '../src/templates/classic.js'

const payload = {
  resumeJson: {
    basics: { name: '<林致远>', title: '高级后端工程师', location: '上海', summary: '稳定性治理' },
    work: [{ company: '星河科技', position: '技术负责人', startDate: '2022-03', endDate: '至今', highlights: ['吞吐提升 3 倍'] }],
    skills: [{ name: 'Java' }],
    projects: [{ name: '交易中台', role: '负责人', description: '统一交易能力' }],
    education: [{ school: '华东理工大学', degree: '本科', major: '计算机科学' }],
    certificates: [{ name: 'AWS SAP', issuer: 'AWS', date: '2025-06' }],
    languages: [{ name: '英语', level: '专业工作沟通' }],
  },
}

test('renders every supported template with the same resume sections', () => {
  for (const code of TEMPLATE_CODES) {
    const html = renderResumeHtml(code, payload)
    assert.match(html, new RegExp(`<body`))
    assert.match(html, /个人概要/)
    assert.match(html, /工作经历/)
    assert.match(html, /专业技能/)
    assert.match(html, /项目经历/)
    assert.match(html, /教育经历/)
    assert.match(html, /专业证书/)
    assert.match(html, /语言能力/)
    assert.match(html, /&lt;林致远&gt;/)
    assert.doesNotMatch(html, /<林致远>/)
  }
})

test('rejects unknown template codes', () => {
  assert.throws(() => renderResumeHtml('unknown', payload), /不支持的简历模板/)
})

test('renders URL-like resume text as escaped text without external resources', () => {
  const html = renderResumeHtml('classic', {
    resumeJson: {
      basics: { name: 'Alice', summary: 'https://portfolio.example.com' },
      work: [{ company: 'file: archive', description: '// internal note' }],
    },
  })

  assert.match(html, /https:\/\/portfolio\.example\.com/)
  assert.doesNotMatch(html, /href=|src=/)
})

test('renders the raw resume JSON sent by the export worker', () => {
  const html = renderResumeHtml('classic', {
    basics: { name: 'Alice' },
    work: [{ company: 'ACME', position: 'Engineer' }],
  })

  assert.match(html, /Alice/)
  assert.match(html, /ACME/)
})

test('applies saved layout settings within safe bounds', () => {
  const html = renderResumeHtml('classic', {
    basics: { name: 'Alice' },
    layout: { fontFamily: 'songti', bodyFontSize: 16, headingFontSize: 18, lineHeight: 2, sectionSpacing: 32, entrySpacing: 22, pagePadding: 80 },
  })

  assert.match(html, /--resume-body-size:12pt/)
  assert.match(html, /--resume-font-family:"Songti SC",SimSun,serif/)
  assert.match(html, /--resume-heading-size:13\.5pt/)
  assert.match(html, /--resume-name-size:33\.23076923076923pt/)
  assert.match(html, /--resume-role-size:14\.538461538461538pt/)
  assert.match(html, /--resume-line-height:2/)
  assert.match(html, /--resume-section-gap:24pt/)
  assert.match(html, /--resume-entry-gap:16\.5pt/)
  assert.match(html, /--paper-pad:60pt/)
})
