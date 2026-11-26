import React, { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { userApi } from '../api/user'
import { formatApiError } from '../api/errors'
import AuthLayout from '../components/AuthLayout'
import { IconAlert, IconEye, IconEyeOff } from '../components/Icons'

export default function Register() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [showConfirmPassword, setShowConfirmPassword] = useState(false)
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
      setError('密码长度不能少于 6 位')
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
      setError(formatApiError(err, '注册失败，请重试'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout>
      <header className="auth-form-head">
        <p className="eyebrow">CREATE / ACCOUNT</p>
        <h1>创建账号</h1>
        <p>注册后即可进入查询工作台，保存数据源与会话记录。</p>
      </header>

      {error && (
        <div className="form-error" role="alert">
          <IconAlert size={15} />
          <span>{error}</span>
        </div>
      )}

      <form className="auth-form" onSubmit={handleSubmit}>
        <div className="field">
          <label htmlFor="register-username">用户名</label>
          <input
            id="register-username"
            className="input"
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="设置登录用户名"
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            required
          />
        </div>

        <div className="field">
          <div className="field-label-row">
            <label htmlFor="register-nickname">昵称</label>
            <span className="field-hint">可选</span>
          </div>
          <input
            id="register-nickname"
            className="input"
            type="text"
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            placeholder="用于工作台显示"
            autoComplete="nickname"
          />
        </div>

        <div className="field">
          <div className="field-label-row">
            <label htmlFor="register-password">密码</label>
            <span className="field-hint">至少 6 位</span>
          </div>
          <div className="password-input-wrap">
            <input
              id="register-password"
              className="input password-input"
              type={showPassword ? 'text' : 'password'}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="设置登录密码"
              autoComplete="new-password"
              required
            />
            <button
              type="button"
              className="password-toggle"
              onClick={() => setShowPassword((visible) => !visible)}
              aria-label={showPassword ? '隐藏密码' : '显示密码'}
              aria-controls="register-password"
              aria-pressed={showPassword}
            >
              {showPassword ? <IconEye size={18} /> : <IconEyeOff size={18} />}
            </button>
          </div>
        </div>

        <div className="field">
          <label htmlFor="register-confirm-password">确认密码</label>
          <div className="password-input-wrap">
            <input
              id="register-confirm-password"
              className="input password-input"
              type={showConfirmPassword ? 'text' : 'password'}
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              placeholder="再次输入密码"
              autoComplete="new-password"
              required
            />
            <button
              type="button"
              className="password-toggle"
              onClick={() => setShowConfirmPassword((visible) => !visible)}
              aria-label={showConfirmPassword ? '隐藏确认密码' : '显示确认密码'}
              aria-controls="register-confirm-password"
              aria-pressed={showConfirmPassword}
            >
              {showConfirmPassword ? (
                <IconEye size={18} />
              ) : (
                <IconEyeOff size={18} />
              )}
            </button>
          </div>
        </div>

        <button
          type="submit"
          className="btn btn-primary btn-block"
          disabled={loading}
        >
          {loading ? '正在创建…' : '创建账号'}
        </button>
      </form>

      <div className="auth-footer">
        <span>已有账号？</span>
        <Link to="/login">返回登录</Link>
      </div>
    </AuthLayout>
  )
}
