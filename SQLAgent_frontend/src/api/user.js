import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  headers: { 'Content-Type': 'application/json' }
})

// 请求拦截器：添加 token
api.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers['satoken'] = token
  }
  return config
})

// 响应拦截器：处理 401
api.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export const userApi = {
  login(username, password) {
    return api.post('/users/login', { username, password })
  },
  register(username, password, nickname) {
    return api.post('/users/register', { username, password, nickname })
  },
  logout() {
    return api.post('/users/logout')
  },
  getCurrentUser() {
    return api.get('/users/me')
  }
}

export default api
