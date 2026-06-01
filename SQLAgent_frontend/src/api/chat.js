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
  getHistory(sessionId) {
    return api.get(`/chat/history/${sessionId}`)
  },
  getSessions() {
    return api.get('/chat/sessions')
  }
}
