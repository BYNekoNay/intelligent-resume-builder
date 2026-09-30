import test from 'node:test'
import assert from 'node:assert/strict'
import { ERROR_CODE_KEYS, extractErrorCode } from '../src/utils/errorCodes.ts'

test('extracts backend business code from an axios-style error', () => {
  const cause = { response: { data: { code: 40901, message: '当前不在等待回答状态', data: null } } }
  assert.equal(extractErrorCode(cause), 40901)
})

test('returns null when the error carries no ApiResponse body', () => {
  assert.equal(extractErrorCode(null), null)
  assert.equal(extractErrorCode('network error'), null)
  assert.equal(extractErrorCode({}), null)
  assert.equal(extractErrorCode({ response: { data: { message: 'no code' } } }), null)
})

test('returns null for non-numeric or non-finite codes', () => {
  assert.equal(extractErrorCode({ response: { data: { code: '40901' } } }), null)
  assert.equal(extractErrorCode({ response: { data: { code: Number.NaN } } }), null)
})

test('error code table covers exactly the ten backend ErrorCode values', () => {
  assert.deepEqual(
    Object.keys(ERROR_CODE_KEYS).map(Number).sort((a, b) => a - b),
    [40001, 40101, 40301, 40302, 40401, 40901, 42901, 50001, 50002, 50003],
  )
})

test('every registered code maps to an errors.* i18n key', () => {
  for (const key of Object.values(ERROR_CODE_KEYS)) {
    assert.match(key, /^errors\.[A-Za-z]+$/, `registered key ${key} must live under the errors.* namespace`)
  }
})
