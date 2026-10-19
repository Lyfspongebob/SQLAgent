import assert from 'node:assert/strict'
import {
  normalizeChatMessage,
  normalizeSqlResults,
  parseLegacySqlResults,
} from './chatMessage.js'

const structured = normalizeSqlResults([{ id: 1, name: 'A' }])
assert.deepEqual(structured.rows, [{ id: 1, name: 'A' }])
assert.equal(structured.legacy, false)

const json = normalizeSqlResults('[{"id":1,"name":"A"}]')
assert.deepEqual(json.rows, [{ id: 1, name: 'A' }])
assert.equal(json.legacy, false)

const legacy = normalizeSqlResults('[{id=1, name=A}, {id=2, name=B}]')
assert.deepEqual(legacy.rows, [
  { id: '1', name: 'A' },
  { id: '2', name: 'B' },
])
assert.equal(legacy.legacy, true)

const raw = normalizeSqlResults('not-a-result')
assert.equal(raw.rows, null)
assert.equal(raw.rawText, 'not-a-result')

const legacyParser = parseLegacySqlResults('[{id=3}]')
assert.deepEqual(legacyParser, [{ id: '3' }])

const dto = normalizeChatMessage({
  sqlResults: null,
  sqlResultText: '[{id=4}]',
  sqlSuccess: true,
})
assert.deepEqual(dto.sqlResults, [{ id: '4' }])
assert.equal(dto.sqlExecutionSuccess, true)
assert.equal(dto.sqlResultIsLegacy, true)

const noResult = normalizeChatMessage({ sqlResults: null, sqlSuccess: false })
assert.equal(noResult.sqlResults, null)
assert.equal(noResult.sqlExecutionSuccess, false)

console.log('chatMessage normalization tests passed')
