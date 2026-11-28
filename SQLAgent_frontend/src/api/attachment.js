import api from './user'

export const attachmentApi = {
  upload(file, sessionId, onUploadProgress) {
    const formData = new FormData()
    formData.append('file', file)

    return api.post('/attachments', formData, {
      params: { sessionId },
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress,
      timeout: 120_000,
    })
  },

  delete(attachmentId) {
    return api.delete(`/attachments/${attachmentId}`)
  },

  contentUrl(attachmentId) {
    return `/api/attachments/${attachmentId}/content`
  },
}
