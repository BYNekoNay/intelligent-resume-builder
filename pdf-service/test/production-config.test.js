import { spawnSync } from 'node:child_process'
import assert from 'node:assert/strict'
import { test } from 'node:test'

test('production startup rejects the development PDF service token', () => {
  const result = spawnSync(process.execPath, ['src/server.js', '--port=3101'], {
    cwd: process.cwd(),
    env: { ...process.env, NODE_ENV: 'production', PDF_SERVICE_TOKEN: 'dev-pdf-token-change-me' },
    encoding: 'utf8',
  })

  assert.equal(result.status, 1)
  assert.match(result.stderr, /PDF_SERVICE_TOKEN must be a non-default secret/)
})

test('production startup rejects the repository placeholder PDF service token', () => {
  const result = spawnSync(process.execPath, ['src/server.js', '--port=3101'], {
    cwd: process.cwd(),
    env: { ...process.env, NODE_ENV: 'production', PDF_SERVICE_TOKEN: 'replace-with-at-least-32-random-characters' },
    encoding: 'utf8',
  })

  assert.equal(result.status, 1)
  assert.match(result.stderr, /PDF_SERVICE_TOKEN must be a non-default secret/)
})
