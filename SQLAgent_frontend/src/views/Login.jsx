import React, { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { userApi } from '../api/user'
import { formatApiError } from '../api/errors'
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
      setError(formatApiError(err, '登录失败，请重试'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout>
      <header className="auth-form-head">
        <p className="eyebrow">SIGN IN / SESSION</p>
        <h1>欢迎回来</h1>
        <p>登录你的账号，继续查看查询记录与执行结果。</p>
      </header>

      {error && (
        <div className="form-error" role="alert">
          <IconAlert size={15} />
          <span>{error}</span>
        </div>
      )}

      <form className="auth-form" onSubmit={handleSubmit}>
        <div className="field">
          <label htmlFor="login-username">用户名</label>
          <input
            id="login-username"
            className="input"
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="请输入用户名"
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            required
          />
        </div>

        <div className="field">
          <div className="field-label-row">
            <label htmlFor="login-password">密码</label>
            <span className="field-hint">区分大小写</span>
          </div>
          <input
            id="login-password"
            className="input"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="请输入密码"
            autoComplete="current-password"
            required
          />
        </div>

        <button
          type="submit"
          className="btn btn-primary btn-block"
          disabled={loading}
        >
          {loading ? '正在验证…' : '进入工作台'}
        </button>
      </form>

      <div className="auth-footer">
        <span>还没有账号？</span>
        <Link to="/register">创建账号</Link>
      </div>
    </AuthLayout>
  )
}
