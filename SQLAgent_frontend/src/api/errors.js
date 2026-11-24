export function getApiError(error, fallback = '请求失败，请稍后重试') {
  const payload = error?.response?.data

  return {
    code: payload?.errorCode || null,
    message: payload?.message || error?.message || fallback,
    requestId: payload?.requestId || error?.response?.headers?.['x-request-id'] || null,
    details: payload?.details || {},
  }
}

export function formatApiError(error, fallback) {
  const result = getApiError(error, fallback)
  return result.requestId
    ? `${result.message}（请求号：${result.requestId}）`
    : result.message
}
