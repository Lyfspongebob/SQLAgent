import React, { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { userApi } from '../api/user'
import AuthLayout from '../components/AuthLayout'
import { IconAlert } from '../components/Icons'

export default function Login() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setLoading(true)

    try {
      const res = await userApi.login(username, password)
      if (res.data.code === 200) {
        localStorage.setItem('token', res.data.data.token)
        localStorage.setItem('user', JSON.stringify(res.data.data.user))
        navigate('/chat')
      } else {
        setError(res.data.message)
      }
    } catch (err) {
      setError(err.response?.data?.message || '登录失败，请重试')
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout>
      <div className="auth-brand">
        <div className="brand-title">欢迎回来</div>
        <div className="brand-subtitle">登录你的账号，开始数据查询</div>
      </div>

      {error && (
        <div className="form-error">
          <IconAlert size={16} />
          {error}
        </div>
      )}

      <form onSubmit={handleSubmit}>
        <div className="field">
          <label>用户名</label>
          <input
            className="input"
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="请输入用户名"
            required
          />
        </div>
        <div className="field">
          <label>密码</label>
          <input
            className="input"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="请输入密码"
            required
          />
        </div>
        <button type="submit" className="btn btn-primary" style={{ width: '100%' }} disabled={loading}>
          {loading ? '登录中...' : '登录'}
        </button>
      </form>

      <div className="auth-footer">
        还没有账号？<Link to="/register">立即注册</Link>
      </div>
    </AuthLayout>
  )
}
