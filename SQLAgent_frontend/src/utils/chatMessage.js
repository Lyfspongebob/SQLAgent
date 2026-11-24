const MAX_LEGACY_RESULT_TEXT = 20000

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function truncateLegacyText(text) {
  if (text.length <= MAX_LEGACY_RESULT_TEXT) return text
  return `${text.slice(0, MAX_LEGACY_RESULT_TEXT)}\n…`
}

function normalizeRows(rows) {
  return rows
    .filter((row) => row !== null && row !== undefined)
    .map((row) => (isPlainObject(row) ? row : { value: row }))
}

function splitTopLevel(text, delimiter = ',') {
  const parts = []
  let start = 0
  let depth = 0
  let quote = null
  let escaped = false

  for (let index = 0; index < text.length; index += 1) {
    const char = text[index]

    if (quote) {
      if (escaped) {
        escaped = false
      } else if (char === '\\') {
        escaped = true
      } else if (char === quote) {
        quote = null
      }
      continue
    }

    if (char === '"' || char === "'") {
      quote = char
    } else if (char === '[' || char === '{' || char === '(') {
      depth += 1
    } else if (char === ']' || char === '}' || char === ')') {
      depth -= 1
    } else if (char === delimiter && depth === 0) {
      parts.push(text.slice(start, index))
      start = index + 1
    }
  }

  parts.push(text.slice(start))
  return parts
}

function splitFirstTopLevel(text, delimiter = '=') {
  let depth = 0
  let quote = null
  let escaped = false

  for (let index = 0; index < text.length; index += 1) {
    const char = text[index]

    if (quote) {
      if (escaped) {
        escaped = false
      } else if (char === '\\') {
        escaped = true
      } else if (char === quote) {
        quote = null
      }
      continue
    }

    if (char === '"' || char === "'") {
      quote = char
    } else if (char === '[' || char === '{' || char === '(') {
      depth += 1
    } else if (char === ']' || char === '}' || char === ')') {
      depth -= 1
    } else if (char === delimiter && depth === 0) {
      return [text.slice(0, index), text.slice(index + 1)]
    }
  }

  return null
}

function unquote(value) {
  const text = value.trim()
  if (text.length < 2) return text

  const first = text[0]
  const last = text[text.length - 1]
  if ((first === '"' || first === "'") && first === last) {
    return text.slice(1, -1)
  }
  return text
}

function parseLegacyRow(rowText) {
  const trimmed = rowText.trim()
  const body = trimmed.startsWith('{') && trimmed.endsWith('}')
    ? trimmed.slice(1, -1)
    : trimmed

  if (!body) return { value: '' }

  const fields = splitTopLevel(body)
  const row = {}

  for (const field of fields) {
    const pair = splitFirstTopLevel(field)
    if (!pair) {
      row.value = unquote(field)
      continue
    }

    const [rawKey, rawValue] = pair
    const key = unquote(rawKey)
    if (key) row[key] = unquote(rawValue)
  }

  return Object.keys(row).length > 0 ? row : { value: unquote(body) }
}

export function parseLegacySqlResults(value) {
  const text = value.trim()
  if (!text.startsWith('[') || !text.endsWith(']')) return null

  const body = text.slice(1, -1).trim()
  if (!body) return []

  const items = splitTopLevel(body)
  if (items.length === 0) return null

  return items.map(parseLegacyRow)
}

export function normalizeSqlResults(value) {
  if (value === null || value === undefined) {
    return { rows: null, rawText: null, legacy: false }
  }

  if (Array.isArray(value)) {
    return { rows: normalizeRows(value), rawText: null, legacy: false }
  }

  if (typeof value !== 'string') {
    return {
      rows: isPlainObject(value) ? [value] : null,
      rawText: isPlainObject(value) ? null : truncateLegacyText(String(value)),
      legacy: true,
    }
  }

  const text = value.trim()
  if (!text || text === 'null') {
    return { rows: null, rawText: null, legacy: false }
  }

  try {
    const parsed = JSON.parse(text)
    if (Array.isArray(parsed)) {
      return { rows: normalizeRows(parsed), rawText: null, legacy: false }
    }
    if (isPlainObject(parsed)) {
      return { rows: [parsed], rawText: null, legacy: false }
    }
  } catch {
    // Historical rows were saved with Java List.toString(), not JSON.
  }

  const legacyRows = parseLegacySqlResults(text)
  if (legacyRows) {
    return { rows: normalizeRows(legacyRows), rawText: null, legacy: true }
  }

  return {
    rows: null,
    rawText: truncateLegacyText(text),
    legacy: true,
  }
}

export function normalizeChatMessage(message) {
  const resultSource = message?.sqlResults ?? message?.sqlResultText
  const result = normalizeSqlResults(resultSource)

  return {
    ...message,
    sqlResults: result.rows,
    sqlResultText: result.rawText ?? (result.rows ? null : message?.sqlResultText ?? null),
    sqlResultIsLegacy: result.legacy,
    sqlExecutionSuccess:
      message?.sqlExecutionSuccess ?? message?.sqlSuccess ?? null,
    sqlErrorMessage: message?.sqlErrorMessage ?? null,
    errorCode: message?.errorCode ?? null,
  }
}
