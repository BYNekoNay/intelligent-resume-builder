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

test('error code table covers exactly the eleven backend ErrorCode values', () => {
  assert.deepEqual(
    Object.keys(ERROR_CODE_KEYS).map(Number).sort((a, b) => a - b),
    [40001, 40101, 40301, 40302, 40401, 40901, 40902, 42901, 50001, 50002, 50003],
  )
})

test('every registered code maps to an errors.* i18n key', () => {
  for (const key of Object.values(ERROR_CODE_KEYS)) {
    assert.match(key, /^errors\.[A-Za-z]+$/, `registered key ${key} must live under the errors.* namespace`)
  }
})

test('archived-version code maps to actionable guidance, not the generic refresh hint', () => {
  // 归档是可逆状态：处置动作是「先恢复该版本」，与乐观锁/状态机冲突的「刷新重试」不同。
  // 前端不透传服务端 message（双语设计），故必须由专属业务码承载可操作指引。
  assert.equal(ERROR_CODE_KEYS[40902], 'errors.versionArchived')
  assert.notEqual(ERROR_CODE_KEYS[40902], ERROR_CODE_KEYS[40901])
})
