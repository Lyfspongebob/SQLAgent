import React, { useState, useRef, useEffect } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import { chatApi } from '../api/chat'
import { datasourceApi } from '../api/datasource'
import ThemeToggle from '../components/ThemeToggle'
import CodeBlock, { CopyButton } from '../components/CodeBlock'
import { normalizeChatMessage } from '../utils/chatMessage'
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

const QUERY_EXAMPLES = [
  '查询本学期每门课程的平均成绩',
  '找出连续两次缺勤的学生记录',
  '按月份汇总订单数量与平均金额',
]

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
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyError, setHistoryError] = useState('')
  const [historyReloadKey, setHistoryReloadKey] = useState(0)
  const messagesEndRef = useRef(null)
  const inputRef = useRef(null)
  const historyRequestRef = useRef(0)
  const navigate = useNavigate()

  const user = JSON.parse(localStorage.getItem('user') || '{}')
  const activeDataSource = dataSources.find(
    (dataSource) => String(dataSource.id) === String(selectedDataSource)
  )
  const activeSession = sessions.find(
    (session) => session.id === currentSessionId
  )
  const isConversationLoading = loadingSessions || historyLoading

  useEffect(() => {
    loadSessions()
    loadDataSources()
  }, [])

  useEffect(() => {
    if (!currentSessionId) {
      historyRequestRef.current += 1
      setMessages([])
      setHistoryLoading(false)
      setHistoryError('')
      return undefined
    }

    const controller = new AbortController()
    setMessages([])
    setHistoryError('')
    loadHistory(currentSessionId, controller.signal)

    return () => controller.abort()
  }, [currentSessionId, historyReloadKey])

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  useEffect(() => {
    const textarea = inputRef.current
    if (!textarea) return

    textarea.style.height = 'auto'
    const maxHeight = Number.parseFloat(window.getComputedStyle(textarea).maxHeight) || 160
    const nextHeight = Math.min(textarea.scrollHeight, maxHeight)

    textarea.style.height = `${nextHeight}px`
    textarea.style.overflowY = textarea.scrollHeight > maxHeight ? 'auto' : 'hidden'
  }, [input])

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

  const loadHistory = async (sessionId, signal) => {
    const requestId = ++historyRequestRef.current
    setHistoryLoading(true)
    setHistoryError('')

    try {
      const res = await chatApi.getHistory(sessionId, { signal })
      if (signal.aborted || requestId !== historyRequestRef.current) return

      if (res.data.code === 200) {
        setMessages((res.data.data || []).map(normalizeChatMessage))
      } else {
        setHistoryError(res.data.message || '加载历史消息失败')
      }
    } catch (err) {
      if (signal.aborted || requestId !== historyRequestRef.current) return
      console.error('加载历史消息失败:', err)
      setHistoryError('加载历史消息失败，请重试。')
    } finally {
      if (!signal.aborted && requestId === historyRequestRef.current) {
        setHistoryLoading(false)
      }
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
    if (!input.trim() || loading || historyLoading) return

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
        const assistantMessage = normalizeChatMessage({
          role: 'assistant',
          content: response.reply,
          sqlGenerated: response.sqlGenerated,
          sqlResults: response.sqlResults,
          sqlExecutionSuccess: response.sqlExecutionSuccess,
          sqlErrorMessage: response.sqlErrorMessage,
          fromKnowledgeBase: response.fromKnowledgeBase
        })
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
          <Link to="/chat" className="nav-item active" aria-current="page">
            <IconChat size={16} />
            <span>查询工作台</span>
          </Link>
          <Link to="/datasource" className="nav-item">
            <IconDatabase size={16} />
            <span>数据源</span>
          </Link>
        </nav>

        <div className="sidebar-action">
          <button className="btn-new-chat" onClick={handleNewSession}>
            <IconPlus size={15} />
            <span>新建查询</span>
          </button>
        </div>

        <section className="session-section" aria-label="最近查询">
          <div className="sidebar-section-head">
            <span>RECENT QUERIES</span>
            <span>{String(sessions.length).padStart(2, '0')}</span>
          </div>

          <div className="session-list">
            {loadingSessions ? (
              <div className="sessions-empty">正在载入查询记录…</div>
            ) : sessions.length === 0 ? (
              <div className="sessions-empty">暂无记录，从一个问题开始。</div>
            ) : (
              sessions.map(session => {
                const isActive = currentSessionId === session.id
                return (
                  <div
                    key={session.id}
                    className={`session-row ${isActive ? 'active' : ''}`}
                  >
                    <button
                      type="button"
                      className="session-item"
                      onClick={() => handleSwitchSession(session.id)}
                      aria-current={isActive ? 'true' : undefined}
                    >
                      <span className="session-title">{session.title || '新对话'}</span>
                      <span className="session-time">
                        {formatTime(session.updateTime || session.createTime)}
                      </span>
                    </button>
                    <button
                      type="button"
                      className="session-delete"
                      onClick={(e) => handleDeleteSession(e, session.id)}
                      title="删除会话"
                      aria-label={`删除会话 ${session.title || '新对话'}`}
                    >
                      <IconX size={13} />
                    </button>
                  </div>
                )
              })
            )}
          </div>
        </section>

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
            <span className="eyebrow">QUERY WORKSPACE</span>
            <h1>{activeSession?.title || '查询工作台'}</h1>
          </div>
          <div className="context-strip" aria-label="当前执行上下文">
            <span className={`context-item ${useKnowledgeBase ? 'is-on' : ''}`}>
              <span className="status-dot" />
              知识库 {useKnowledgeBase ? 'ON' : 'OFF'}
            </span>
            <span className={`context-item ${useSqlAgent ? 'is-on' : ''}`}>
              <span className="status-dot" />
              {activeDataSource ? activeDataSource.name : '未选择数据源'}
            </span>
          </div>
        </header>

        <div className="chat-body">
          <div className="transcript">
            {isConversationLoading && (
              <div className="history-status" role="status" aria-live="polite">
                <span className="signal-line" aria-hidden="true" />
                <span>正在载入会话记录…</span>
              </div>
            )}

            {!isConversationLoading && historyError && (
              <div className="history-error" role="alert">
                <span>{historyError}</span>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() => setHistoryReloadKey((value) => value + 1)}
                >
                  重试
                </button>
              </div>
            )}

            {!isConversationLoading && !historyError && messages.length === 0 && (
              <section className="empty-query">
                <div className="empty-query-head">
                  <p className="eyebrow">READY / NO ACTIVE QUERY</p>
                  <h2>从一个可验证的问题开始</h2>
                  <p>
                    用自然语言描述你想查询的结果。选择数据源并启用 SQL Agent 后，
                    系统会生成 SQL，并把执行状态与结果留在同一份记录里。
                  </p>
                </div>

                <div className="query-example-list" aria-label="示例查询">
                  {QUERY_EXAMPLES.map((example, index) => (
                    <div className="query-example" key={example}>
                      <span className="query-example-index">
                        {String(index + 1).padStart(2, '0')}
                      </span>
                      <span>{example}</span>
                      <span className="query-example-arrow">示例</span>
                    </div>
                  ))}
                </div>

                <div className="empty-query-note">
                  <IconDatabase size={14} />
                  <span>先在下方设置执行上下文，再输入查询。</span>
                </div>
              </section>
            )}

            {messages.map((msg, index) => {
              const isUser = msg.role === 'user'
              return (
                <article key={index} className={`message ${msg.role}`}>
                  {(!isUser || msg.fromKnowledgeBase || msg.sqlGenerated) && (
                    <div className="message-meta">
                      <span className="message-author">
                        {isUser ? '你' : 'SQL Agent'}
                      </span>
                      {msg.fromKnowledgeBase && <span className="token">RAG</span>}
                      {msg.sqlGenerated && <span className="token">SQL</span>}
                      {msg.sqlExecutionSuccess === true && (
                        <span className="inline-status success">
                          <span className="status-dot" />
                          执行成功
                        </span>
                      )}
                    </div>
                  )}

                  <div className={`message-content ${msg.role}`}>
                    <div className="transcript-copy">
                      <ReactMarkdown components={{ pre: CodeBlock }}>
                        {msg.content}
                      </ReactMarkdown>
                    </div>

                    {msg.sqlResults && msg.sqlResults.length > 0 && (
                      <div className="result-panel">
                        <div className="result-head">
                          <span>RESULT SET</span>
                          <span>
                            {msg.sqlResults.length} ROWS
                            {msg.sqlResultIsLegacy ? ' · LEGACY' : ''}
                          </span>
                        </div>
                        <div className="table-scroll">
                          <table className="sql-result-table">
                            <thead>
                              <tr>
                                {Object.keys(msg.sqlResults[0]).map(col => (
                                  <th key={col}>{col}</th>
                                ))}
                              </tr>
                            </thead>
                            <tbody>
                              {msg.sqlResults.map((row, rowIndex) => (
                                <tr key={rowIndex}>
                                  {Object.values(row).map((val, valueIndex) => (
                                    <td key={valueIndex}>{String(val)}</td>
                                  ))}
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    )}

                    {msg.sqlResultText && (
                      <div className="result-panel legacy-result-panel">
                        <div className="result-head">
                          <span>LEGACY RESULT</span>
                          <span>RAW TEXT</span>
                        </div>
                        <pre className="legacy-result-text">{msg.sqlResultText}</pre>
                      </div>
                    )}

                    {msg.sqlGenerated && (
                      <div className="sql-block">
                        <div className="sql-head">
                          <span className="sql-head-label">
                            <IconCode size={14} />
                            GENERATED SQL
                          </span>
                          <div className="sql-head-actions">
                            {msg.sqlExecutionSuccess === false && (
                              <span className="sql-status error">
                                <span className="status-dot" />
                                执行失败
                              </span>
                            )}
                            {msg.sqlExecutionSuccess === true && (
                              <span className="sql-status success">
                                <span className="status-dot" />
                                EXECUTED
                              </span>
                            )}
                            <CopyButton text={msg.sqlGenerated} />
                          </div>
                        </div>
                        <pre><code>{msg.sqlGenerated}</code></pre>
                        {msg.sqlExecutionSuccess === false && msg.sqlErrorMessage && (
                          <div className="sql-error">{msg.sqlErrorMessage}</div>
                        )}
                      </div>
                    )}
                  </div>
                </article>
              )
            })}

            {loading && (
              <div className="message assistant">
                <div className="message-meta">
                  <span className="message-author">SQL Agent</span>
                </div>
                <div className="loading-status" role="status" aria-live="polite">
                  <span className="signal-line" aria-hidden="true" />
                  <span>正在生成并校验 SQL</span>
                  <span className="loading-caret" aria-hidden="true">_</span>
                </div>
              </div>
            )}

            <div ref={messagesEndRef} />
          </div>
        </div>

        <div className="chat-footer">
          <div className="composer">
            <div className="composer-head">
              <span className="composer-label">EXECUTION CONTEXT</span>
              <div className="chat-options">
                <button
                  type="button"
                  className={`mode-toggle ${useKnowledgeBase ? 'active' : ''}`}
                  onClick={() => setUseKnowledgeBase(!useKnowledgeBase)}
                  aria-pressed={useKnowledgeBase}
                  title="开启后，问题将先查询知识库"
                >
                  <IconBook size={14} />
                  <span>知识库</span>
                </button>

                <button
                  type="button"
                  className={`mode-toggle ${useSqlAgent ? 'active' : ''}`}
                  onClick={() => setUseSqlAgent(!useSqlAgent)}
                  aria-pressed={useSqlAgent}
                  title="开启后，将使用 SQL Agent 模式"
                >
                  <IconDatabase size={14} />
                  <span>SQL Agent</span>
                </button>

                {useSqlAgent && (
                  <label className="select-group">
                    <span>DATA SOURCE</span>
                    <select
                      className="select"
                      value={selectedDataSource}
                      onChange={(e) => setSelectedDataSource(e.target.value)}
                    >
                      <option value="">选择数据源…</option>
                      {dataSources.map(dataSource => (
                        <option key={dataSource.id} value={dataSource.id}>
                          {dataSource.name} ({dataSource.dbType})
                        </option>
                      ))}
                    </select>
                  </label>
                )}
              </div>
            </div>

            <div className="input-row">
              <label className="sr-only" htmlFor="query-input">查询内容</label>
              <textarea
                ref={inputRef}
                id="query-input"
                className="chat-input"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder={
                  historyLoading
                    ? '正在载入会话记录…'
                    : useSqlAgent && !selectedDataSource
                    ? '请先选择数据源…'
                    : useSqlAgent
                      ? '输入自然语言查询，例如：查询所有用户的姓名和邮箱…'
                      : '输入你的问题…'
                }
                rows={1}
                disabled={historyLoading || (useSqlAgent && !selectedDataSource)}
              />
              <button
                type="button"
                className="send-btn"
                onClick={handleSend}
                disabled={
                  loading ||
                  historyLoading ||
                  !input.trim() ||
                  (useSqlAgent && !selectedDataSource)
                }
                aria-label={loading ? '正在处理查询' : '发送查询'}
                title={loading ? '正在处理查询' : '发送查询'}
              >
                {loading
                  ? <span className="send-progress" aria-hidden="true" />
                  : <IconSend size={17} />}
              </button>
            </div>

            <div className="composer-help">
              <span>Enter 发送 · Shift + Enter 换行</span>
              <span>执行结果需结合数据上下文核对</span>
            </div>
          </div>
        </div>
      </main>
    </div>
  )
}
