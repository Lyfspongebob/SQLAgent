import React, { useState, useEffect } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { datasourceApi } from '../api/datasource'
import ThemeToggle from '../components/ThemeToggle'
import {
  Logo,
  IconChat,
  IconDatabase,
  IconPlus,
  IconLink,
  IconSave,
  IconPencil,
  IconTrash,
  IconAlert,
} from '../components/Icons'

export default function DataSourceConfig() {
  const [configs, setConfigs] = useState([])
  const [showForm, setShowForm] = useState(false)
  const [editingId, setEditingId] = useState(null)
  const [form, setForm] = useState({
    name: '',
    dbType: 'mysql',
    host: 'localhost',
    port: 3306,
    databaseName: '',
    username: 'root',
    password: '',
    description: ''
  })
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [testing, setTesting] = useState(false)
  const navigate = useNavigate()

  const user = JSON.parse(localStorage.getItem('user') || '{}')

  useEffect(() => {
    loadConfigs()
  }, [])

  const loadConfigs = async () => {
    try {
      const res = await datasourceApi.list()
      if (res.data.code === 200) {
        setConfigs(res.data.data || [])
      }
    } catch (err) {
      console.error('加载数据源失败:', err)
    }
  }

  const handleLogout = () => {
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    navigate('/login')
  }

  const resetForm = () => {
    setForm({
      name: '',
      dbType: 'mysql',
      host: 'localhost',
      port: 3306,
      databaseName: '',
      username: 'root',
      password: '',
      description: ''
    })
    setEditingId(null)
    setShowForm(false)
    setError('')
  }

  const handleEdit = (config) => {
    setForm({
      name: config.name,
      dbType: config.dbType,
      host: config.host,
      port: config.port,
      databaseName: config.databaseName,
      username: config.username,
      password: '',
      description: config.description || ''
    })
    setEditingId(config.id)
    setShowForm(true)
    setError('')
  }

  const handleTestConnection = async () => {
    setTesting(true)
    setError('')
    try {
      const res = await datasourceApi.testConnection({
        id: editingId,
        dbType: form.dbType,
        host: form.host,
        port: form.port,
        databaseName: form.databaseName,
        username: form.username,
        password: form.password
      })
      if (res.data.code === 200) {
        alert('连接成功！')
      } else {
        alert('连接失败：' + res.data.message)
      }
    } catch (err) {
      alert('连接失败：' + (err.response?.data?.message || err.message))
    } finally {
      setTesting(false)
    }
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setLoading(true)

    try {
      let res
      if (editingId) {
        res = await datasourceApi.update({ ...form, id: editingId })
      } else {
        res = await datasourceApi.save(form)
      }

      if (res.data.code === 200) {
        resetForm()
        loadConfigs()
      } else {
        setError(res.data.message)
      }
    } catch (err) {
      setError(err.response?.data?.message || '保存失败')
    } finally {
      setLoading(false)
    }
  }

  const handleDelete = async (id) => {
    if (!window.confirm('确定要删除这个数据源配置吗？')) return

    try {
      await datasourceApi.delete(id)
      loadConfigs()
    } catch (err) {
      alert('删除失败：' + (err.response?.data?.message || err.message))
    }
  }

  const getInitial = (name) => {
    return name ? name.charAt(0).toUpperCase() : 'U'
  }

  const dbTypeLabels = {
    mysql: 'MySQL',
    postgresql: 'PostgreSQL',
    sqlserver: 'SQL Server'
  }

  return (
    <div className="app">
      {/* 侧边栏 */}
      <div className="sidebar">
        <div className="sidebar-head">
          <div className="sidebar-brand">
            <Logo size={34} />
            <div>
              <div className="brand-name">SQL Agent</div>
              <div className="brand-sub">智能 SQL 助手</div>
            </div>
          </div>
          <ThemeToggle />
        </div>

        <div className="sidebar-nav">
          <Link to="/chat" className="nav-item">
            <IconChat size={18} />
            对话
          </Link>
          <div className="nav-item active">
            <IconDatabase size={18} />
            数据源配置
          </div>
        </div>

        <div className="sidebar-footer">
          <div className="user-box">
            <div className="avatar">{getInitial(user.nickname || user.username)}</div>
            <span className="user-name">{user.nickname || user.username}</span>
            <button className="logout-btn" onClick={handleLogout}>退出</button>
          </div>
        </div>
      </div>

      {/* 主内容区域 */}
      <div className="page">
        <div className="page-head">
          <div className="page-title">
            <div className="title-icon"><IconDatabase size={20} /></div>
            <div>
              <h1>数据源配置</h1>
              <p>管理用于查询的数据库连接</p>
            </div>
          </div>
          <button
            className="btn btn-primary"
            onClick={() => { resetForm(); setShowForm(!showForm) }}
          >
            {showForm ? '取消' : (<><IconPlus size={16} />新增数据源</>)}
          </button>
        </div>

        {error && (
          <div className="form-error">
            <IconAlert size={16} />
            {error}
          </div>
        )}

        {/* 远程连接提示 */}
        <div className="tip-card">
          <span className="tip-icon"><IconAlert size={18} /></span>
          <div className="tip-content">
            <strong>请确保你的数据库允许远程连接</strong>
            <p>SQLAgent 部署在云端服务器，需要通过公网访问你的数据库。如果数据库在本地，请使用内网穿透工具（如 <a href="https://ngrok.com" target="_blank" rel="noopener noreferrer">ngrok</a>、<a href="https://github.com/fatedier/frp" target="_blank" rel="noopener noreferrer">frp</a>）或将数据库迁移到云服务（如 <a href="https://www.aliyun.com/product/rds" target="_blank" rel="noopener noreferrer">阿里云 RDS</a>）。</p>
          </div>
        </div>

        {/* 配置表单 */}
        {showForm && (
          <div className="card">
            <div className="card-title">{editingId ? '编辑数据源' : '新增数据源'}</div>
            <form onSubmit={handleSubmit}>
              <div className="field">
                <label>数据源名称</label>
                <input
                  className="input"
                  type="text"
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  placeholder="例如：生产数据库"
                  required
                />
              </div>

              <div className="form-grid">
                <div className="field">
                  <label>数据库类型</label>
                  <select
                    className="input"
                    value={form.dbType}
                    onChange={(e) => {
                      const portMap = { mysql: 3306, postgresql: 5432, sqlserver: 1433 }
                      setForm({ ...form, dbType: e.target.value, port: portMap[e.target.value] })
                    }}
                  >
                    <option value="mysql">MySQL</option>
                    <option value="postgresql">PostgreSQL</option>
                    <option value="sqlserver">SQL Server</option>
                  </select>
                </div>
                <div className="field">
                  <label>主机地址</label>
                  <input
                    className="input"
                    type="text"
                    value={form.host}
                    onChange={(e) => setForm({ ...form, host: e.target.value })}
                    placeholder="localhost"
                    required
                  />
                </div>

                <div className="field">
                  <label>端口</label>
                  <input
                    className="input"
                    type="number"
                    value={form.port}
                    onChange={(e) => setForm({ ...form, port: parseInt(e.target.value) })}
                    required
                  />
                </div>
                <div className="field">
                  <label>数据库名</label>
                  <input
                    className="input"
                    type="text"
                    value={form.databaseName}
                    onChange={(e) => setForm({ ...form, databaseName: e.target.value })}
                    placeholder="数据库名称"
                    required
                  />
                </div>

                <div className="field">
                  <label>用户名</label>
                  <input
                    className="input"
                    type="text"
                    value={form.username}
                    onChange={(e) => setForm({ ...form, username: e.target.value })}
                    placeholder="root"
                    required
                  />
                </div>
                <div className="field">
                  <label>密码</label>
                  <input
                    className="input"
                    type="password"
                    value={form.password}
                    onChange={(e) => setForm({ ...form, password: e.target.value })}
                    placeholder={editingId ? '留空则不修改' : '请输入密码'}
                    required={!editingId}
                  />
                </div>

                <div className="field full">
                  <label>描述（可选）</label>
                  <input
                    className="input"
                    type="text"
                    value={form.description}
                    onChange={(e) => setForm({ ...form, description: e.target.value })}
                    placeholder="数据源用途说明"
                  />
                </div>
              </div>

              <div className="btn-group">
                <button
                  type="button"
                  className="btn btn-test"
                  onClick={handleTestConnection}
                  disabled={testing}
                >
                  <IconLink size={16} />
                  {testing ? '测试中...' : '测试连接'}
                </button>
                <button
                  type="submit"
                  className="btn btn-primary"
                  disabled={loading}
                >
                  <IconSave size={16} />
                  {loading ? '保存中...' : '保存'}
                </button>
              </div>
            </form>
          </div>
        )}

        {/* 数据源列表 */}
        <div className="ds-list">
          {configs.length === 0 ? (
            <div className="empty-state">
              <div className="empty-icon"><IconDatabase size={26} /></div>
              <p>还没有配置数据源</p>
              <p className="empty-hint">点击上方「新增数据源」按钮开始配置</p>
            </div>
          ) : (
            configs.map(config => (
              <div key={config.id} className="ds-item">
                <div className="ds-info">
                  <div className="ds-name">
                    {config.name}
                    <span className="ds-type">{dbTypeLabels[config.dbType] || config.dbType}</span>
                  </div>
                  <div className="ds-meta">
                    {config.host}:{config.port} · {config.databaseName}
                    {config.description && ` · ${config.description}`}
                  </div>
                </div>
                <div className="ds-actions">
                  <button className="btn-edit" onClick={() => handleEdit(config)}>
                    <IconPencil size={14} />
                    编辑
                  </button>
                  <button className="btn-delete" onClick={() => handleDelete(config.id)}>
                    <IconTrash size={14} />
                    删除
                  </button>
                </div>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  )
}
