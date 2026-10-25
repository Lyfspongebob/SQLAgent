import api from './user'

export const chatApi = {
  sendMessage(message, sessionId, useKnowledgeBase, useSqlAgent, dataSourceConfigId) {
    return api.post('/chat/send', {
      message,
      sessionId,
      useKnowledgeBase,
      useSqlAgent,
      dataSourceConfigId
    })
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
