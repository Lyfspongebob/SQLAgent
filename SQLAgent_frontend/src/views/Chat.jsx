import React, { useState, useRef, useEffect } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import { chatApi } from '../api/chat'
import { datasourceApi } from '../api/datasource'
import ThemeToggle from '../components/ThemeToggle'
import {
  Logo,
  IconPlus,
  IconChat,
  IconDatabase,
  IconBook,
  IconCode,
  IconSend,
  IconX,
} from '../components/Icons'

export default function Chat() {
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [useKnowledgeBase, setUseKnowledgeBase] = useState(false)
  const [useSqlAgent, setUseSqlAgent] = useState(false)
  const [dataSources, setDataSources] = useState([])
  const [selectedDataSource, setSelectedDataSource] = useState('')
  const [sessions, setSessions] = useState([])
  const [currentSessionId, setCurrentSessionId] = useState(null)
  const [loadingSessions, setLoadingSessions] = useState(false)
  const messagesEndRef = useRef(null)
  const navigate = useNavigate()

  const user = JSON.parse(localStorage.getItem('user') || '{}')

  useEffect(() => {
    loadSessions()
    loadDataSources()
  }, [])

  useEffect(() => {
    if (currentSessionId) {
      loadHistory(currentSessionId)
    } else {
      setMessages([])
    }
  }, [currentSessionId])

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const loadSessions = async () => {
    setLoadingSessions(true)
    try {
      const res = await chatApi.getSessions()
      if (res.data.code === 200) {
        const sessionList = res.data.data || []
        setSessions(sessionList)
        if (sessionList.length > 0 && !currentSessionId) {
          setCurrentSessionId(sessionList[0].id)
        }
      }
    } catch (err) {
      console.error('加载会话列表失败:', err)
    } finally {
      setLoadingSessions(false)
    }
  }

  const loadHistory = async (sessionId) => {
    try {
      const res = await chatApi.getHistory(sessionId)
      if (res.data.code === 200) {
        setMessages(res.data.data || [])
      }
    } catch (err) {
      console.error('加载历史消息失败:', err)
    }
  }

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

  const handleNewSession = async () => {
    try {
      const res = await chatApi.createSession('新对话')
      if (res.data.code === 200) {
        const newSession = res.data.data
        setSessions(prev => [newSession, ...prev])
        setCurrentSessionId(newSession.id)
        setMessages([])
      }
    } catch (err) {
      console.error('创建会话失败:', err)
    }
  }

  const handleDeleteSession = async (e, sessionId) => {
    e.stopPropagation()
    if (!window.confirm('确定要删除这个会话吗？')) return

    try {
      const res = await chatApi.deleteSession(sessionId)
      if (res.data.code === 200) {
        const updatedSessions = sessions.filter(s => s.id !== sessionId)
        setSessions(updatedSessions)
        if (currentSessionId === sessionId) {
          if (updatedSessions.length > 0) {
            setCurrentSessionId(updatedSessions[0].id)
          } else {
            setCurrentSessionId(null)
            setMessages([])
          }
        }
      }
    } catch (err) {
      console.error('删除会话失败:', err)
    }
  }

  const handleSwitchSession = (sessionId) => {
    if (sessionId !== currentSessionId) {
      setCurrentSessionId(sessionId)
    }
  }

  const handleLogout = () => {
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    navigate('/login')
  }

  const handleSend = async () => {
    if (!input.trim() || loading) return

    let sessionId = currentSessionId
    if (!sessionId) {
      try {
        const res = await chatApi.createSession('新对话')
        if (res.data.code === 200) {
          const newSession = res.data.data
          sessionId = newSession.id
          setSessions(prev => [newSession, ...prev])
          setCurrentSessionId(sessionId)
        } else {
          return
        }
      } catch (err) {
        console.error('创建会话失败:', err)
        return
      }
    }

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
        loadSessions()
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

  const formatTime = (timeStr) => {
    if (!timeStr) return ''
    const date = new Date(timeStr)
    const now = new Date()
    const isToday = date.toDateString() === now.toDateString()
    if (isToday) {
      return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    }
    return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
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

        <div className="sidebar-new">
          <button className="btn-new-chat" onClick={handleNewSession}>
            <IconPlus size={16} />
            新建对话
          </button>
        </div>

        <div className="session-list">
          {loadingSessions ? (
            <div className="sessions-empty">加载中...</div>
          ) : sessions.length === 0 ? (
            <div className="sessions-empty">暂无对话，点击上方新建</div>
          ) : (
            sessions.map(session => (
              <div
                key={session.id}
                className={`session-item ${currentSessionId === session.id ? 'active' : ''}`}
                onClick={() => handleSwitchSession(session.id)}
              >
                <div className="session-info">
                  <div className="session-title">{session.title || '新对话'}</div>
                  <div className="session-time">{formatTime(session.updateTime || session.createTime)}</div>
                </div>
                <button
                  className="session-delete"
                  onClick={(e) => handleDeleteSession(e, session.id)}
                  title="删除会话"
                >
                  <IconX size={14} />
                </button>
              </div>
            ))
          )}
        </div>

        <div className="sidebar-nav">
          <Link to="/datasource" className="nav-item">
            <IconDatabase size={18} />
            数据源配置
          </Link>
        </div>

        <div className="sidebar-footer">
          <div className="user-box">
            <div className="avatar">{getInitial(user.nickname || user.username)}</div>
            <span className="user-name">{user.nickname || user.username}</span>
            <button className="logout-btn" onClick={handleLogout}>退出</button>
          </div>
        </div>
      </div>

      {/* 主聊天区域 */}
      <div className="main">
        <div className="chat-body">
          {messages.length === 0 && (
            <div className="welcome">
              <Logo size={64} />
              <h2>欢迎使用 SQL Agent</h2>
              <p>用自然语言查询数据库，轻松编写与优化 SQL</p>
              <div className="welcome-tips">
                <span className="welcome-tip">自然语言查询</span>
                <span className="welcome-tip">编写与优化 SQL</span>
                <span className="welcome-tip">分析表结构</span>
              </div>
            </div>
          )}

          {messages.map((msg, index) => (
            <div key={index} className={`message-row ${msg.role}`}>
              <div className="msg-avatar">
                {msg.role === 'user' ? getInitial(user.nickname || user.username) : <IconChat size={16} />}
              </div>
              <div className="msg-body">
                <div className="msg-meta">
                  {msg.role === 'user' ? '你' : 'AI 助手'}
                  {msg.fromKnowledgeBase && (
                    <span className="msg-badge"><IconBook size={11} /> 知识库</span>
                  )}
                  {msg.sqlGenerated && (
                    <span className="msg-badge"><IconCode size={11} /> SQL</span>
                  )}
                </div>
                <div className={`bubble ${msg.role}`}>
                  <ReactMarkdown>{msg.content}</ReactMarkdown>

                  {msg.sqlResults && msg.sqlResults.length > 0 && (
                    <div style={{ overflowX: 'auto' }}>
                      <table className="sql-result-table">
                        <thead>
                          <tr>
                            {Object.keys(msg.sqlResults[0]).map(col => (
                              <th key={col}>{col}</th>
                            ))}
                          </tr>
                        </thead>
                        <tbody>
                          {msg.sqlResults.map((row, i) => (
                            <tr key={i}>
                              {Object.values(row).map((val, j) => (
                                <td key={j}>{String(val)}</td>
                              ))}
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}

                  {msg.sqlGenerated && (
                    <div className="sql-block">
                      <div className="sql-head">
                        <IconCode size={14} />
                        生成的 SQL
                      </div>
                      <pre><code>{msg.sqlGenerated}</code></pre>
                      {msg.sqlExecutionSuccess === false && (
                        <div className="sql-status error">
                          执行失败：{msg.sqlErrorMessage}
                        </div>
                      )}
                      {msg.sqlExecutionSuccess === true && (
                        <div className="sql-status success">执行成功</div>
                      )}
                    </div>
                  )}
                </div>
              </div>
            </div>
          ))}

          {loading && (
            <div className="message-row assistant">
              <div className="msg-avatar"><IconChat size={16} /></div>
              <div className="msg-body">
                <div className="msg-meta">AI 助手</div>
                <div className="bubble assistant">
                  <div className="typing">
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
        <div className="chat-footer">
          <div className="chat-footer-inner">
            <div className="chat-options">
              <button
                className={`chip ${useKnowledgeBase ? 'active' : ''}`}
                onClick={() => setUseKnowledgeBase(!useKnowledgeBase)}
                title="开启后，问题将先查询知识库"
              >
                <span className="chip-dot"></span>
                <IconBook size={14} />
                知识库
              </button>

              <button
                className={`chip ${useSqlAgent ? 'active' : ''}`}
                onClick={() => setUseSqlAgent(!useSqlAgent)}
                title="开启后，将使用 SQL Agent 模式"
              >
                <span className="chip-dot"></span>
                <IconDatabase size={14} />
                SQL Agent
              </button>

              {useSqlAgent && (
                <select
                  className="select"
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

            <div className="input-row">
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
                {loading ? '...' : (<><IconSend size={16} />发送</>)}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
