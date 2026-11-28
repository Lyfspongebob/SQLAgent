import api from './user'

export const chatApi = {
  sendMessage(
    message,
    sessionId,
    useKnowledgeBase,
    useSqlAgent,
    dataSourceConfigId,
    attachmentIds = []
  ) {
    return api.post('/chat/send', {
      message,
      sessionId,
      useKnowledgeBase,
      useSqlAgent,
      dataSourceConfigId,
      attachmentIds
    })
  },
  async streamMessage(payload, { onStep, onResult } = {}) {
    const token = localStorage.getItem('token')
    const response = await fetch('/api/chat/send/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { satoken: token } : {}),
      },
      body: JSON.stringify(payload),
    })

    if (!response.ok) {
      if (response.status === 401) {
        localStorage.removeItem('token')
        localStorage.removeItem('user')
        window.location.href = '/login'
      }
      let body = null
      try {
        body = await response.json()
      } catch {
        // 非 JSON 错误由调用方使用状态码展示。
      }
      const error = new Error(
        body?.message || `请求失败（HTTP ${response.status}）`
      )
      error.response = { data: body, status: response.status }
      throw error
    }

    if (!response.body) {
      throw new Error('浏览器不支持流式响应')
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let finalResult = null

    const processEvent = (block) => {
      const lines = block.split(/\r?\n/)
      let eventName = 'message'
      const dataLines = []

      for (const line of lines) {
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          dataLines.push(line.slice(5).trimStart())
        }
      }

      if (dataLines.length === 0) return
      let data
      try {
        data = JSON.parse(dataLines.join('\n'))
      } catch {
        return
      }

      if (eventName === 'step') {
        onStep?.(data)
      } else if (eventName === 'result') {
        finalResult = data
        onResult?.(data)
      } else if (eventName === 'error') {
        const error = new Error(data?.message || 'Agent 执行失败')
        error.response = { data }
        throw error
      }
    }

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      buffer = buffer.replace(/\r\n/g, '\n')
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const block = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        processEvent(block)
        boundary = buffer.indexOf('\n\n')
      }
    }

    buffer += decoder.decode()
    if (buffer.trim()) {
      processEvent(buffer)
    }
    if (!finalResult) {
      throw new Error('Agent 流式响应未返回最终结果')
    }
    return finalResult
  },
  getHistory(sessionId, config = {}) {
    return api.get(`/chat/history/${sessionId}`, config)
  },
  getSessions() {
    return api.get('/chat/sessions')
  },
  createSession(title) {
    return api.post('/chat/session/create', { title })
  },
  deleteSession(sessionId) {
    return api.delete(`/chat/session/${sessionId}`)
  },
  updateSessionTitle(sessionId, title) {
    return api.put(`/chat/session/${sessionId}`, { title })
  }
}
