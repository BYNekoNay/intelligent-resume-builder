import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { renderResumeHtml, TEMPLATE_CODES } from '../src/templates/classic.js'

const sharedResume = JSON.parse(readFileSync(
  new URL('../../test-fixtures/resume-all-sections.json', import.meta.url),
  'utf8',
))
const payload = { resumeJson: sharedResume }

test('renders every supported template with the shared resume contract', () => {
  const markers = [
    'SUMMARY_MARKER', 'OBJECTIVE_MARKER', 'LINK_MARKER', 'WORK_MARKER',
    'VOLUNTEERING_MARKER', 'SKILL_MARKER', 'PROJECT_MARKER', 'EDUCATION_MARKER',
    'COURSE_MARKER', 'CERTIFICATE_MARKER', 'PUBLICATION_MARKER', 'AWARD_MARKER',
    'LANGUAGE_MARKER', 'CUSTOM_ONE_MARKER', 'CUSTOM_TWO_MARKER',
  ]

  for (const code of TEMPLATE_CODES) {
    const html = renderResumeHtml(code, payload)
    assert.match(html, /<body/)
    for (const marker of markers) assert.match(html, new RegExp(marker))
    assert.ok(html.indexOf('AWARD_MARKER') < html.indexOf('OBJECTIVE_MARKER'))
    assert.ok(html.indexOf('OBJECTIVE_MARKER') < html.indexOf('WORK_MARKER'))
    assert.doesNotMatch(html, /IGNORED_UNKNOWN_MARKER/)
    assert.match(html, /Shanghai/)
    assert.doesNotMatch(html, /\[object Object\]/)
  }
})

test('rejects unknown template codes', () => {
  assert.throws(() => renderResumeHtml('unknown', payload))
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

test('does not crash when optional sections are missing from the payload', () => {
  const html = renderResumeHtml('classic', {
    resumeJson: { basics: { name: 'Alice', title: 'Engineer' } },
  })
  assert.match(html, /Alice/)
  assert.match(html, /Engineer/)
})

test('does not crash when an unknown section key appears in resumeJson', () => {
  const html = renderResumeHtml('classic', {
    resumeJson: {
      basics: { name: 'Bob' },
      unknownFutureField: [{ data: 'should be ignored' }],
      work: [{ company: 'ACME' }],
    },
  })
  assert.match(html, /Bob/)
  assert.match(html, /ACME/)
  assert.doesNotMatch(html, /should be ignored/)
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
