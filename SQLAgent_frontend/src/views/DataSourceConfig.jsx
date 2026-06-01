import React, { useState, useEffect } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { datasourceApi } from '../api/datasource'

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
        id: editingId, // 编辑时传入 id，便于后端获取已保存的密码
        dbType: form.dbType,
        host: form.host,
        port: form.port,
        databaseName: form.databaseName,
        username: form.username,
        password: form.password
      })
      if (res.data.code === 200) {
        alert('✅ 连接成功！')
      } else {
        alert('❌ 连接失败：' + res.data.message)
      }
    } catch (err) {
      alert('❌ 连接失败：' + (err.response?.data?.message || err.message))
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
    <div className="chat-container">
      {/* 侧边栏 */}
      <div className="chat-sidebar">
        <div className="sidebar-header">
          <h2>SQL Agent</h2>
          <p>智能SQL助手</p>
        </div>

        <div className="sidebar-menu">
          <Link to="/chat" className="menu-item">
            <span className="icon">💬</span>
            对话
          </Link>
          <div className="menu-item active">
            <span className="icon">🔌</span>
            数据源配置
          </div>
        </div>

        <div className="sidebar-footer">
          <div className="user-info">
            <div className="user-avatar">
              {getInitial(user.nickname || user.username)}
            </div>
            <span className="user-name">{user.nickname || user.username}</span>
            <button className="logout-btn" onClick={handleLogout}>退出</button>
          </div>
        </div>
      </div>

      {/* 主内容区域 */}
      <div className="config-container" style={{ flex: 1, overflow: 'auto' }}>
        <div className="config-header">
          <h1>🔌 数据源配置</h1>
          <button
            className="btn btn-primary"
            style={{ width: 'auto', padding: '10px 20px' }}
            onClick={() => { resetForm(); setShowForm(!showForm) }}
          >
            {showForm ? '取消' : '+ 新增数据源'}
          </button>
        </div>

        {error && <div className="error-message">{error}</div>}

        {/* 远程连接提示 */}
        <div className="remote-access-tip">
          <span className="tip-icon">⚠️</span>
          <div className="tip-content">
            <strong>请确保你的数据库允许远程连接</strong>
            <p>SQLAgent 部署在云端服务器，需要通过公网访问你的数据库。如果数据库在本地，请使用内网穿透工具（如 <a href="https://ngrok.com" target="_blank" rel="noopener noreferrer">ngrok</a>、<a href="https://github.com/fatedier/frp" target="_blank" rel="noopener noreferrer">frp</a>）或将数据库迁移到云服务（如 <a href="https://www.aliyun.com/product/rds" target="_blank" rel="noopener noreferrer">阿里云 RDS</a>）。</p>
          </div>
        </div>

        {/* 配置表单 */}
        {showForm && (
          <div className="config-form">
            <h2>{editingId ? '编辑数据源' : '新增数据源'}</h2>
            <form onSubmit={handleSubmit}>
              <div className="form-group">
                <label>数据源名称</label>
                <input
                  type="text"
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  placeholder="例如：生产数据库"
                  required
                />
              </div>

              <div className="form-row">
                <div className="form-group">
                  <label>数据库类型</label>
                  <select
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
                <div className="form-group">
                  <label>主机地址</label>
                  <input
                    type="text"
                    value={form.host}
                    onChange={(e) => setForm({ ...form, host: e.target.value })}
                    placeholder="localhost"
                    required
                  />
                </div>
              </div>

              <div className="form-row">
                <div className="form-group">
                  <label>端口</label>
                  <input
                    type="number"
                    value={form.port}
                    onChange={(e) => setForm({ ...form, port: parseInt(e.target.value) })}
                    required
                  />
                </div>
                <div className="form-group">
                  <label>数据库名</label>
                  <input
                    type="text"
                    value={form.databaseName}
                    onChange={(e) => setForm({ ...form, databaseName: e.target.value })}
                    placeholder="数据库名称"
                    required
                  />
                </div>
              </div>

              <div className="form-row">
                <div className="form-group">
                  <label>用户名</label>
                  <input
                    type="text"
                    value={form.username}
                    onChange={(e) => setForm({ ...form, username: e.target.value })}
                    placeholder="root"
                    required
                  />
                </div>
                <div className="form-group">
                  <label>密码</label>
                  <input
                    type="password"
                    value={form.password}
                    onChange={(e) => setForm({ ...form, password: e.target.value })}
                    placeholder={editingId ? '留空则不修改' : '请输入密码'}
                    required={!editingId}
                  />
                </div>
              </div>

              <div className="form-group">
                <label>描述（可选）</label>
                <input
                  type="text"
                  value={form.description}
                  onChange={(e) => setForm({ ...form, description: e.target.value })}
                  placeholder="数据源用途说明"
                />
              </div>

              <div className="btn-group">
                <button
                  type="button"
                  className="btn btn-test"
                  onClick={handleTestConnection}
                  disabled={testing}
                >
                  {testing ? '测试中...' : '🔗 测试连接'}
                </button>
                <button
                  type="submit"
                  className="btn btn-primary"
                  disabled={loading}
                >
                  {loading ? '保存中...' : '💾 保存'}
                </button>
              </div>
            </form>
          </div>
        )}

        {/* 数据源列表 */}
        <div className="config-list">
          {configs.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '40px', color: '#999' }}>
              <div style={{ fontSize: '48px', marginBottom: '16px' }}>📡</div>
              <p>还没有配置数据源</p>
              <p style={{ fontSize: '13px', marginTop: '8px' }}>
                点击上方"新增数据源"按钮开始配置
              </p>
            </div>
          ) : (
            configs.map(config => (
              <div key={config.id} className="config-item">
                <div className="config-item-info">
                  <h3>{config.name}</h3>
                  <p>
                    {dbTypeLabels[config.dbType] || config.dbType} · {config.host}:{config.port} · {config.databaseName}
                    {config.description && ` · ${config.description}`}
                  </p>
                </div>
                <div className="config-item-actions">
                  <button className="btn-edit" onClick={() => handleEdit(config)}>
                    编辑
                  </button>
                  <button className="btn-delete" onClick={() => handleDelete(config.id)}>
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
