import React, { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { userApi } from '../api/user'
import AuthLayout from '../components/AuthLayout'
import { IconAlert } from '../components/Icons'

export default function Register() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [nickname, setNickname] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')

    if (password !== confirmPassword) {
      setError('两次输入的密码不一致')
      return
    }

    if (password.length < 6) {
      setError('密码长度不能少于6位')
      return
    }

    setLoading(true)

    try {
      const res = await userApi.register(username, password, nickname)
      if (res.data.code === 200) {
        navigate('/login')
      } else {
        setError(res.data.message)
      }
    } catch (err) {
      setError(err.response?.data?.message || '注册失败，请重试')
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout>
      <div className="auth-brand">
        <div className="brand-title">创建账号</div>
        <div className="brand-subtitle">注册后即可使用 SQL Agent</div>
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
          <label>昵称（可选）</label>
          <input
            className="input"
            type="text"
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            placeholder="请输入昵称"
          />
        </div>
        <div className="field">
          <label>密码</label>
          <input
            className="input"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="请输入密码（至少6位）"
            required
          />
        </div>
        <div className="field">
          <label>确认密码</label>
          <input
            className="input"
            type="password"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
            placeholder="请再次输入密码"
            required
          />
        </div>
        <button type="submit" className="btn btn-primary" style={{ width: '100%' }} disabled={loading}>
          {loading ? '注册中...' : '注册'}
        </button>
      </form>

      <div className="auth-footer">
        已有账号？<Link to="/login">立即登录</Link>
      </div>
    </AuthLayout>
  )
}
