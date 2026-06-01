import React, { useState, useEffect } from 'react'
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom'
import Login from './views/Login'
import Register from './views/Register'
import Chat from './views/Chat'
import DataSourceConfig from './views/DataSourceConfig'
import { userApi } from './api/user'

// 认证检查组件
function PrivateRoute({ children }) {
  const [checking, setChecking] = useState(true)
  const [valid, setValid] = useState(false)

  useEffect(() => {
    const token = localStorage.getItem('token')
    if (!token) {
      setValid(false)
      setChecking(false)
      return
    }

    // 向后端验证 token 是否有效
    userApi.getCurrentUser()
      .then(() => {
        setValid(true)
        setChecking(false)
      })
      .catch(() => {
        localStorage.removeItem('token')
        localStorage.removeItem('user')
        setValid(false)
        setChecking(false)
      })
  }, [])

  if (checking) {
    return <div className="loading-container">验证登录状态...</div>
  }

  if (!valid) {
    return <Navigate to="/login" replace />
  }

  return children
}

export default function App() {
  return (
    <Router>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/register" element={<Register />} />
        <Route path="/chat" element={
          <PrivateRoute>
            <Chat />
          </PrivateRoute>
        } />
        <Route path="/datasource" element={
          <PrivateRoute>
            <DataSourceConfig />
          </PrivateRoute>
        } />
        <Route path="/" element={<Navigate to="/chat" replace />} />
      </Routes>
    </Router>
  )
}
