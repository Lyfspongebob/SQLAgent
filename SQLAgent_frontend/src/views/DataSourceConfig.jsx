import React, { useState, useEffect } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { datasourceApi } from '../api/datasource'
import { formatApiError } from '../api/errors'
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
      alert('连接失败：' + formatApiError(err, '连接测试失败'))
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
      setError(formatApiError(err, '保存失败'))
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
      alert('删除失败：' + formatApiError(err, '删除失败'))
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
      <aside className="sidebar">
        <div className="sidebar-head">
          <div className="sidebar-brand">
            <Logo size={24} />
            <div>
              <div className="brand-name">SQL Agent</div>
              <div className="brand-sub">QUERY WORKSPACE</div>
            </div>
          </div>
          <ThemeToggle />
        </div>

        <nav className="sidebar-nav" aria-label="主导航">
          <div className="nav-section-label">WORKSPACE</div>
          <Link to="/chat" className="nav-item">
            <IconChat size={16} />
            <span>查询工作台</span>
          </Link>
          <Link to="/datasource" className="nav-item active" aria-current="page">
            <IconDatabase size={16} />
            <span>数据源</span>
          </Link>
        </nav>

        <div className="sidebar-note">
          <span className="nav-section-label">CONNECTION REGISTRY</span>
          <p>集中管理查询可用的数据库连接与认证信息。</p>
          <div className="sidebar-metric">
            <span>{String(configs.length).padStart(2, '0')}</span>
            <span>SAVED CONNECTIONS</span>
          </div>
        </div>

        <div className="sidebar-footer">
          <div className="user-box">
            <div className="avatar">{getInitial(user.nickname || user.username)}</div>
            <div className="user-copy">
              <span className="user-name">{user.nickname || user.username || '未登录用户'}</span>
              <span className="user-role">QUERY OPERATOR</span>
            </div>
            <button className="logout-btn" onClick={handleLogout}>退出</button>
          </div>
        </div>
      </aside>

      <main className="main">
        <header className="workspace-bar">
          <div className="workspace-heading">
            <span className="eyebrow">WORKSPACE / CONNECTIONS</span>
            <h1>数据源</h1>
          </div>
          <div className="page-actions">
            <span className="record-count">{configs.length} 个连接</span>
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => {
                resetForm()
                setShowForm(!showForm)
              }}
            >
              {showForm ? '取消' : (
                <>
                  <IconPlus size={15} />
                  新增数据源
                </>
              )}
            </button>
          </div>
        </header>

        <div className="page-scroll">
          <div className="page-content">
            {error && (
              <div className="form-error" role="alert">
                <IconAlert size={15} />
                <span>{error}</span>
              </div>
            )}

            <div className="notice-strip">
              <span className="notice-icon">
                <IconAlert size={15} />
              </span>
              <div className="notice-copy">
                <strong>请确认数据库允许远程连接</strong>
                <p>
                  SQL Agent 部署在云端服务器时，需要通过公网访问数据库。数据库在本地时，
                  可使用
                  {' '}
                  <a href="https://ngrok.com" target="_blank" rel="noopener noreferrer">ngrok</a>
                  {' '}
                  或
                  {' '}
                  <a href="https://github.com/fatedier/frp" target="_blank" rel="noopener noreferrer">frp</a>
                  {' '}
                  建立内网穿透，或迁移到云数据库服务。
                </p>
              </div>
            </div>

            <div className="data-layout">
              <section className="panel connection-panel" aria-labelledby="connection-list-title">
                <div className="panel-head">
                  <div>
                    <p className="eyebrow">CONNECTION REGISTRY</p>
                    <h2 id="connection-list-title">已保存连接</h2>
                  </div>
                  <span className="record-count">{String(configs.length).padStart(2, '0')}</span>
                </div>

                <div className="connection-list">
                  {configs.length === 0 ? (
                    <div className="empty-state">
                      <span className="empty-index">00</span>
                      <p>还没有配置数据源</p>
                      <p className="empty-hint">点击右上角「新增数据源」建立第一个连接。</p>
                    </div>
                  ) : (
                    configs.map(config => {
                      const isEditing = showForm && editingId === config.id
                      return (
                        <article
                          key={config.id}
                          className={`connection-row ${isEditing ? 'active' : ''}`}
                        >
                          <div className="connection-main">
                            <div className="connection-title-row">
                              <span className="connection-name">{config.name}</span>
                              <span className="type-token">
                                {dbTypeLabels[config.dbType] || config.dbType}
                              </span>
                            </div>
                            <div className="connection-endpoint">
                              <span>{config.host}:{config.port}</span>
                              <span className="endpoint-divider">/</span>
                              <span>{config.databaseName}</span>
                            </div>
                            {config.description && (
                              <p className="connection-description">{config.description}</p>
                            )}
                          </div>

                          <div className="connection-actions">
                            <button
                              type="button"
                              className="icon-action"
                              onClick={() => handleEdit(config)}
                              aria-label={`编辑数据源 ${config.name}`}
                            >
                              <IconPencil size={14} />
                              <span>编辑</span>
                            </button>
                            <button
                              type="button"
                              className="icon-action danger"
                              onClick={() => handleDelete(config.id)}
                              aria-label={`删除数据源 ${config.name}`}
                            >
                              <IconTrash size={14} />
                              <span>删除</span>
                            </button>
                          </div>
                        </article>
                      )
                    })
                  )}
                </div>
              </section>

              <section className="panel detail-panel" aria-labelledby="connection-detail-title">
                {showForm ? (
                  <>
                    <div className="panel-head">
                      <div>
                        <p className="eyebrow">
                          {editingId ? 'EDIT CONNECTION' : 'NEW CONNECTION'}
                        </p>
                        <h2 id="connection-detail-title">
                          {editingId ? '编辑数据源' : '新增数据源'}
                        </h2>
                      </div>
                      <button type="button" className="btn btn-ghost btn-sm" onClick={resetForm}>
                        取消
                      </button>
                    </div>

                    <div className="form-body">
                      <form onSubmit={handleSubmit}>
                        <section className="form-section">
                          <div className="section-heading">
                            <span>01</span>
                            <div>
                              <h3>连接信息</h3>
                              <p>用于定位数据库实例与目标库。</p>
                            </div>
                          </div>

                          <div className="field">
                            <label htmlFor="data-source-name">数据源名称</label>
                            <input
                              id="data-source-name"
                              className="input"
                              type="text"
                              value={form.name}
                              onChange={(e) => setForm({ ...form, name: e.target.value })}
                              placeholder="例如：课程实验数据库"
                              autoComplete="off"
                              required
                            />
                          </div>

                          <div className="form-grid">
                            <div className="field">
                              <label htmlFor="data-source-type">数据库类型</label>
                              <select
                                id="data-source-type"
                                className="input"
                                value={form.dbType}
                                onChange={(e) => {
                                  const portMap = {
                                    mysql: 3306,
                                    postgresql: 5432,
                                    sqlserver: 1433
                                  }
                                  setForm({
                                    ...form,
                                    dbType: e.target.value,
                                    port: portMap[e.target.value]
                                  })
                                }}
                              >
                                <option value="mysql">MySQL</option>
                                <option value="postgresql">PostgreSQL</option>
                                <option value="sqlserver">SQL Server</option>
                              </select>
                            </div>

                            <div className="field">
                              <label htmlFor="data-source-host">主机地址</label>
                              <input
                                id="data-source-host"
                                className="input"
                                type="text"
                                value={form.host}
                                onChange={(e) => setForm({ ...form, host: e.target.value })}
                                placeholder="localhost"
                                autoComplete="off"
                                spellCheck={false}
                                required
                              />
                            </div>

                            <div className="field">
                              <label htmlFor="data-source-port">端口</label>
                              <input
                                id="data-source-port"
                                className="input"
                                type="number"
                                inputMode="numeric"
                                value={form.port}
                                onChange={(e) => setForm({
                                  ...form,
                                  port: parseInt(e.target.value)
                                })}
                                required
                              />
                            </div>

                            <div className="field">
                              <label htmlFor="data-source-database">数据库名</label>
                              <input
                                id="data-source-database"
                                className="input"
                                type="text"
                                value={form.databaseName}
                                onChange={(e) => setForm({
                                  ...form,
                                  databaseName: e.target.value
                                })}
                                placeholder="数据库名称"
                                autoComplete="off"
                                spellCheck={false}
                                required
                              />
                            </div>
                          </div>
                        </section>

                        <section className="form-section">
                          <div className="section-heading">
                            <span>02</span>
                            <div>
                              <h3>认证信息</h3>
                              <p>凭据仅用于建立数据库连接。</p>
                            </div>
                          </div>

                          <div className="form-grid">
                            <div className="field">
                              <label htmlFor="data-source-username">用户名</label>
                              <input
                                id="data-source-username"
                                className="input"
                                type="text"
                                value={form.username}
                                onChange={(e) => setForm({
                                  ...form,
                                  username: e.target.value
                                })}
                                placeholder="root"
                                autoComplete="username"
                                spellCheck={false}
                                required
                              />
                            </div>

                            <div className="field">
                              <div className="field-label-row">
                                <label htmlFor="data-source-password">密码</label>
                                {editingId && <span className="field-hint">留空则不修改</span>}
                              </div>
                              <input
                                id="data-source-password"
                                className="input"
                                type="password"
                                value={form.password}
                                onChange={(e) => setForm({
                                  ...form,
                                  password: e.target.value
                                })}
                                placeholder={editingId ? '留空保持原密码' : '输入数据库密码'}
                                autoComplete="new-password"
                                required={!editingId}
                              />
                            </div>
                          </div>
                        </section>

                        <section className="form-section">
                          <div className="section-heading">
                            <span>03</span>
                            <div>
                              <h3>备注</h3>
                              <p>说明连接用途，便于后续识别。</p>
                            </div>
                          </div>

                          <div className="field">
                            <label htmlFor="data-source-description">描述</label>
                            <input
                              id="data-source-description"
                              className="input"
                              type="text"
                              value={form.description}
                              onChange={(e) => setForm({
                                ...form,
                                description: e.target.value
                              })}
                              placeholder="例如：数据库原理课程实验数据"
                              autoComplete="off"
                            />
                          </div>
                        </section>

                        <div className="form-actions">
                          <button
                            type="button"
                            className="btn btn-test"
                            onClick={handleTestConnection}
                            disabled={testing}
                          >
                            <IconLink size={14} />
                            {testing ? '正在测试…' : '测试连接'}
                          </button>
                          <button
                            type="submit"
                            className="btn btn-primary"
                            disabled={loading}
                          >
                            <IconSave size={14} />
                            {loading ? '正在保存…' : '保存连接'}
                          </button>
                        </div>
                      </form>
                    </div>
                  </>
                ) : (
                  <div className="detail-empty">
                    <span className="detail-index">02</span>
                    <p className="eyebrow">NO CONNECTION SELECTED</p>
                    <h2 id="connection-detail-title">选择一个连接进行编辑</h2>
                    <p>从左侧列表选择数据源，或创建一个新的数据库连接。</p>
                    <button
                      type="button"
                      className="btn btn-ghost"
                      onClick={() => {
                        resetForm()
                        setShowForm(true)
                      }}
                    >
                      <IconPlus size={14} />
                      新增数据源
                    </button>
                  </div>
                )}
              </section>
            </div>
          </div>
        </div>
      </main>
    </div>
  )
}
