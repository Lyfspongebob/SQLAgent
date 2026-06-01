import api from './user'

export const datasourceApi = {
  list() {
    return api.get('/datasource/list')
  },
  getById(id) {
    return api.get(`/datasource/${id}`)
  },
  save(config) {
    return api.post('/datasource/save', config)
  },
  update(config) {
    return api.put('/datasource/update', config)
  },
  delete(id) {
    return api.delete(`/datasource/${id}`)
  },
  testConnection(config) {
    return api.post('/datasource/test-connection', config)
  }
}
