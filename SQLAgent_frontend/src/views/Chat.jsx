import React, { useState, useRef, useEffect } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import { chatApi } from '../api/chat'
import { datasourceApi } from '../api/datasource'

export default function Chat() {
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [useKnowledgeBase, setUseKnowledgeBase] = useState(false)
  const [useSqlAgent, setUseSqlAgent] = useState(false)
  const [dataSources, setDataSources] = useState([])
  const [selectedDataSource, setSelectedDataSource] = useState('')
  const [sessionId] = useState(Date.now())
  const messagesEndRef = useRef(null)
  const navigate = useNavigate()

  const user = JSON.parse(localStorage.getItem('user') || '{}')

  useEffect(() => {
    loadDataSources()
  }, [])

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const loadDataSources = async () => {
    try {
      const res = await datasourceApi.list()
      if (res.data.code === 200) {
        setDataSources(res.data.data || [])
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

  const handleSend = async () => {
    if (!input.trim() || loading) return

    const userMessage = input.trim()
    setInput('')
    setMessages(prev => [...prev, { role: 'user', content: userMessage }])
    setLoading(true)

    try {
      const res = await chatApi.sendMessage(
        userMessage,
        sessionId,
        useKnowledgeBase,
        useSqlAgent,
        selectedDataSource || null
      )

      if (res.data.code === 200) {
        const response = res.data.data
        const assistantMessage = {
          role: 'assistant',
          content: response.reply,
          sqlGenerated: response.sqlGenerated,
          sqlResults: response.sqlResults,
          sqlExecutionSuccess: response.sqlExecutionSuccess,
          sqlErrorMessage: response.sqlErrorMessage,
          fromKnowledgeBase: response.fromKnowledgeBase
        }
        setMessages(prev => [...prev, assistantMessage])
      }
    } catch (err) {
      setMessages(prev => [...prev, {
        role: 'assistant',
        content: '抱歉，请求失败，请重试。错误：' + (err.response?.data?.message || err.message)
      }])
    } finally {
      setLoading(false)
    }
  }

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  const getInitial = (name) => {
    return name ? name.charAt(0).toUpperCase() : 'U'
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
          <div className="menu-item active">
            <span className="icon">💬</span>
            对话
          </div>
          <Link to="/datasource" className="menu-item">
            <span className="icon">🔌</span>
            数据源配置
          </Link>
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

      {/* 主聊天区域 */}
      <div className="chat-main">
        <div className="chat-messages">
          {messages.length === 0 && (
            <div style={{
              textAlign: 'center',
              padding: '60px 20px',
              color: '#999'
            }}>
              <div style={{ fontSize: '48px', marginBottom: '16px' }}>🤖</div>
              <h2 style={{ color: '#333', marginBottom: '8px' }}>欢迎使用 SQL Agent</h2>
              <p>我可以帮您：</p>
              <p>• 用自然语言查询数据库</p>
              <p>• 编写和优化 SQL 语句</p>
              <p>• 分析数据库表结构</p>
              <p style={{ marginTop: '16px', fontSize: '12px' }}>
                请先在"数据源配置"中添加数据库连接，然后开启"SQL Agent"模式
              </p>
            </div>
          )}

          {messages.map((msg, index) => (
            <div key={index} className={`message ${msg.role}`}>
              <div className="message-label">
                {msg.role === 'user' ? '你' : 'AI 助手'}
                {msg.fromKnowledgeBase && ' 📚 知识库'}
                {msg.sqlGenerated && ' 🗄️ SQL'}
              </div>
              <div className="message-content">
                <ReactMarkdown>{msg.content}</ReactMarkdown>
                {msg.sqlGenerated && (
                  <div style={{ marginTop: '8px' }}>
                    <div style={{
                      background: '#f0f5ff',
                      border: '1px solid #d6e4ff',
                      borderRadius: '8px',
                      padding: '8px 12px',
                      fontSize: '12px',
                      color: '#666'
                    }}>
                      <strong>生成的 SQL：</strong>
                      <pre style={{
                        background: '#1e1e1e',
                        color: '#d4d4d4',
                        padding: '8px',
                        borderRadius: '4px',
                        marginTop: '4px',
                        overflow: 'auto'
                      }}>{msg.sqlGenerated}</pre>
                      {msg.sqlExecutionSuccess === false && (
                        <div style={{ color: '#ff4d4f', marginTop: '4px' }}>
                          ❌ 执行失败：{msg.sqlErrorMessage}
                        </div>
                      )}
                      {msg.sqlExecutionSuccess === true && (
                        <div style={{ color: '#52c41a', marginTop: '4px' }}>
                          ✅ 执行成功
                        </div>
                      )}
                    </div>
                  </div>
                )}
              </div>
            </div>
          ))}

          {loading && (
            <div className="message assistant">
              <div className="message-label">AI 助手</div>
              <div className="message-content">
                <div className="loading">
                  <div className="loading-dots">
                    <span></span>
                    <span></span>
                    <span></span>
                  </div>
                </div>
              </div>
            </div>
          )}

          <div ref={messagesEndRef} />
        </div>

        {/* 输入区域 */}
        <div className="chat-input-area">
          <div className="chat-options">
            {/* 知识库开关 - 类似 DeepSeek 的深度思考按钮 */}
            <button
              className={`option-toggle ${useKnowledgeBase ? 'active' : ''}`}
              onClick={() => setUseKnowledgeBase(!useKnowledgeBase)}
              title="开启后，问题将先查询知识库"
            >
              <span className="toggle-dot"></span>
              📚 知识库
            </button>

            {/* SQL Agent 开关 */}
            <button
              className={`option-toggle ${useSqlAgent ? 'active' : ''}`}
              onClick={() => setUseSqlAgent(!useSqlAgent)}
              title="开启后，将使用SQL Agent模式"
            >
              <span className="toggle-dot"></span>
              🗄️ SQL Agent
            </button>

            {/* 数据源选择 */}
            {useSqlAgent && (
              <select
                className="datasource-select"
                value={selectedDataSource}
                onChange={(e) => setSelectedDataSource(e.target.value)}
              >
                <option value="">选择数据源...</option>
                {dataSources.map(ds => (
                  <option key={ds.id} value={ds.id}>
                    {ds.name} ({ds.dbType})
                  </option>
                ))}
              </select>
            )}
          </div>

          <div className="chat-input-wrapper">
            <textarea
              className="chat-input"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={
                useSqlAgent && !selectedDataSource
                  ? '请先选择数据源...'
                  : useSqlAgent
                    ? '请输入自然语言查询，例如：查询所有用户的姓名和邮箱...'
                    : '请输入您的问题...'
              }
              rows={1}
              disabled={useSqlAgent && !selectedDataSource}
            />
            <button
              className="send-btn"
              onClick={handleSend}
              disabled={loading || !input.trim() || (useSqlAgent && !selectedDataSource)}
            >
              {loading ? '...' : '发送'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
