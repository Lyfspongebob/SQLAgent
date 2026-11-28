import React, { useState, useRef, useEffect } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import { chatApi } from '../api/chat'
import { attachmentApi } from '../api/attachment'
import { datasourceApi } from '../api/datasource'
import { formatApiError } from '../api/errors'
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
  IconPaperclip,
  IconImage,
  IconFileText,
} from '../components/Icons'

const QUERY_EXAMPLES = [
  '查询本学期每门课程的平均成绩',
  '找出连续两次缺勤的学生记录',
  '按月份汇总订单数量与平均金额',
]

const MAX_ATTACHMENTS = 4
const MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024
const MAX_DOCUMENT_BYTES = 20 * 1024 * 1024
const SUPPORTED_FILE_EXTENSIONS = new Set([
  'png', 'jpg', 'jpeg', 'gif',
  'pdf', 'doc', 'docx', 'xls', 'xlsx',
  'txt', 'md', 'markdown', 'csv', 'json', 'jsonl', 'ndjson',
  'sql', 'log', 'yaml', 'yml', 'xml',
])
const ATTACHMENT_ACCEPT = [
  '.png', '.jpg', '.jpeg', '.gif',
  '.pdf', '.doc', '.docx', '.xls', '.xlsx',
  '.txt', '.md', '.markdown', '.csv', '.json', '.jsonl', '.ndjson',
  '.sql', '.log', '.yaml', '.yml', '.xml',
].join(',')

function createLocalAttachmentId() {
  return globalThis.crypto?.randomUUID?.() ||
    `attachment-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function formatAttachmentSize(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 KB'
  if (bytes >= 1024 * 1024) {
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  }
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
}

function fileExtension(name) {
  const parts = String(name || '').toLowerCase().split('.')
  return parts.length > 1 ? parts.pop() : ''
}

function attachmentKindForFile(file) {
  if (file.type?.startsWith('image/')) return 'IMAGE'
  const extension = fileExtension(file.name)
  if (extension === 'pdf') return 'PDF'
  if (['doc', 'docx'].includes(extension)) return 'WORD'
  if (['xls', 'xlsx'].includes(extension)) return 'EXCEL'
  return 'TEXT'
}

function maxAttachmentBytes(file) {
  const extension = fileExtension(file.name)
  return ['pdf', 'doc', 'docx', 'xls', 'xlsx'].includes(extension)
    ? MAX_DOCUMENT_BYTES
    : MAX_ATTACHMENT_BYTES
}

function AttachmentList({ attachments, onRemove, disabled = false }) {
  return (
    <div className="attachment-list" aria-label="附件">
      {attachments.map((attachment) => {
        const isImage = attachment.kind === 'IMAGE'
        const isUploading = attachment.status === 'uploading'
        const hasError = attachment.status === 'error'

        return (
          <div
            className={`attachment-chip ${isImage ? 'is-image' : 'is-file'} ${
              hasError ? 'has-error' : ''
            }`}
            key={attachment.localId || attachment.id}
          >
            <span className="attachment-icon" aria-hidden="true">
              {isImage
                ? <IconImage size={15} />
                : <IconFileText size={15} />}
            </span>
            <span className="attachment-copy">
              <span className="attachment-name" title={attachment.name}>
                {attachment.name}
              </span>
              <span className="attachment-meta">
                {hasError
                  ? '上传失败'
                  : isUploading
                    ? `上传中 ${attachment.progress || 0}%`
                    : formatAttachmentSize(attachment.sizeBytes)}
              </span>
            </span>
            {onRemove && (
              <button
                type="button"
                className="attachment-remove"
                onClick={() => onRemove(attachment)}
                disabled={disabled || isUploading}
                title={isUploading ? '正在上传' : '移除附件'}
                aria-label={`移除附件 ${attachment.name}`}
              >
                <IconX size={12} />
              </button>
            )}
            {isUploading && (
              <span
                className="attachment-progress"
                style={{ width: `${attachment.progress || 0}%` }}
                aria-hidden="true"
              />
            )}
          </div>
        )
      })}
    </div>
  )
}

function upsertAgentStep(steps, nextStep) {
  const index = steps.findIndex((step) => step.id === nextStep.id)
  if (index < 0) return [...steps, nextStep]
  const updated = [...steps]
  updated[index] = { ...steps[index], ...nextStep }
  return updated
}

function AgentWorkflow({ steps, live = false }) {
  if (!steps?.length) return null

  const finished = steps.filter(
    (step) => step.status === 'completed' || step.status === 'failed'
  ).length
  const hasFailure = steps.some((step) => step.status === 'failed')

  return (
    <details
      className={`agent-workflow ${live ? 'is-live' : ''} ${
        hasFailure ? 'has-failure' : ''
      }`}
      open={live ? true : undefined}
    >
      <summary>
        <span className="agent-workflow-label">AGENT WORKFLOW</span>
        <span className="agent-workflow-count">
          {live ? '执行中' : '查看步骤'} · {finished}/{steps.length}
        </span>
      </summary>
      <div className="agent-step-list">
        {steps.map((step) => (
          <div
            className={`agent-step ${step.status || 'pending'}`}
            key={step.id}
          >
            <span className="agent-step-dot" aria-hidden="true" />
            <div className="agent-step-copy">
              <div className="agent-step-title">
                <span>{step.title}</span>
                <span className="agent-step-type">
                  {step.type === 'tool'
                    ? 'TOOL'
                    : step.type === 'answer'
                      ? 'ANSWER'
                      : 'MODEL'}
                </span>
              </div>
              {step.summary && (
                <div className="agent-step-summary">{step.summary}</div>
              )}
              {step.detail && (
                <div className="agent-step-detail">{step.detail}</div>
              )}
            </div>
          </div>
        ))}
      </div>
    </details>
  )
}

export default function Chat() {
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [pendingAttachments, setPendingAttachments] = useState([])
  const [attachmentError, setAttachmentError] = useState('')
  const [isDraggingFiles, setIsDraggingFiles] = useState(false)
  const [activeAgentSteps, setActiveAgentSteps] = useState([])
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
  const fileInputRef = useRef(null)
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
  const isUploadingAttachments = pendingAttachments.some(
    (attachment) => attachment.status === 'uploading'
  )
  const readyAttachments = pendingAttachments.filter(
    (attachment) => attachment.status === 'ready' && attachment.id
  )

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

  const ensureSession = async () => {
    if (currentSessionId) return currentSessionId

    const res = await chatApi.createSession('新对话')
    if (res.data.code !== 200 || !res.data.data?.id) {
      throw new Error(res.data.message || '创建会话失败')
    }

    const newSession = res.data.data
    setSessions(prev => [newSession, ...prev])
    setCurrentSessionId(newSession.id)
    return newSession.id
  }

  const clearPendingAttachments = ({ deleteRemote = true } = {}) => {
    const attachments = pendingAttachments
    setPendingAttachments([])
    setAttachmentError('')

    if (!deleteRemote) return
    attachments.forEach((attachment) => {
      if (!attachment.id || attachment.status !== 'ready') return
      attachmentApi.delete(attachment.id).catch(() => {
        // 会话切换时清理失败不应阻塞界面。
      })
    })
  }

  const handleFilesSelected = async (fileList) => {
    const files = Array.from(fileList || [])
    if (
      files.length === 0 ||
      loading ||
      isConversationLoading ||
      isUploadingAttachments
    ) {
      return
    }

    const remainingSlots = MAX_ATTACHMENTS - pendingAttachments.length
    if (remainingSlots <= 0) {
      setAttachmentError(`每条消息最多上传 ${MAX_ATTACHMENTS} 个附件。`)
      return
    }

    const messages = []
    const candidates = files.slice(0, remainingSlots)
    if (files.length > remainingSlots) {
      messages.push(`每条消息最多上传 ${MAX_ATTACHMENTS} 个附件，已忽略超出部分。`)
    }

    const accepted = candidates.filter((file) => {
      const extension = fileExtension(file.name)
      const isSupportedImage = ['image/png', 'image/jpeg', 'image/gif']
        .includes(file.type)
      if (!isSupportedImage && !SUPPORTED_FILE_EXTENSIONS.has(extension)) {
        messages.push(`${file.name} 的格式暂不支持。`)
        return false
      }
      const maxBytes = maxAttachmentBytes(file)
      if (file.size > maxBytes) {
        messages.push(
          `${file.name} 超过 ${maxBytes === MAX_DOCUMENT_BYTES ? '20 MB' : '5 MB'} 限制。`
        )
        return false
      }
      return true
    })

    if (accepted.length === 0) {
      setAttachmentError(messages.join(' '))
      return
    }

    let sessionId
    try {
      sessionId = await ensureSession()
    } catch (err) {
      setAttachmentError(formatApiError(err, '创建会话失败，无法上传附件'))
      return
    }

    const placeholders = accepted.map((file) => ({
      localId: createLocalAttachmentId(),
      name: file.name,
      sizeBytes: file.size,
      kind: attachmentKindForFile(file),
      status: 'uploading',
      progress: 0,
      file,
    }))
    setAttachmentError(messages.join(' '))
    setPendingAttachments(prev => [...prev, ...placeholders])

    for (const placeholder of placeholders) {
      try {
        const res = await attachmentApi.upload(
          placeholder.file,
          sessionId,
          (event) => {
            const total = event.total || placeholder.file.size || 1
            const progress = Math.min(
              99,
              Math.max(1, Math.round((event.loaded / total) * 100))
            )
            setPendingAttachments(prev => prev.map((item) => (
              item.localId === placeholder.localId
                ? { ...item, progress }
                : item
            )))
          }
        )

        if (res.data.code !== 200 || !res.data.data) {
          throw new Error(res.data.message || '上传失败')
        }

        const uploaded = res.data.data
        setPendingAttachments(prev => prev.map((item) => (
          item.localId === placeholder.localId
            ? {
                ...item,
                ...uploaded,
                localId: placeholder.localId,
                status: 'ready',
                progress: 100,
                file: undefined,
              }
            : item
        )))
      } catch (err) {
        setPendingAttachments(prev => prev.map((item) => (
          item.localId === placeholder.localId
            ? { ...item, status: 'error', progress: 0, file: undefined }
            : item
        )))
        setAttachmentError(
          formatApiError(err, `${placeholder.name} 上传失败`)
        )
      }
    }
  }

  const handleRemoveAttachment = async (attachment) => {
    setPendingAttachments(prev => prev.filter(
      (item) => item.localId !== attachment.localId
    ))
    setAttachmentError('')

    if (!attachment.id || attachment.status !== 'ready') return
    try {
      await attachmentApi.delete(attachment.id)
    } catch (err) {
      console.warn('清理附件失败:', err)
    }
  }

  const handleDropFiles = (event) => {
    event.preventDefault()
    setIsDraggingFiles(false)
    handleFilesSelected(event.dataTransfer.files)
  }

  const handleDragLeave = (event) => {
    if (
      event.relatedTarget instanceof Node &&
      event.currentTarget.contains(event.relatedTarget)
    ) return
    setIsDraggingFiles(false)
  }

  const handleNewSession = async () => {
    if (isUploadingAttachments) return
    try {
      const res = await chatApi.createSession('新对话')
      if (res.data.code === 200) {
        clearPendingAttachments()
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
      if (isUploadingAttachments) return
      clearPendingAttachments()
      setCurrentSessionId(sessionId)
    }
  }

  const handleLogout = () => {
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    navigate('/login')
  }

  const handleSend = async () => {
    if (
      (!input.trim() && readyAttachments.length === 0) ||
      loading ||
      isConversationLoading ||
      isUploadingAttachments
    ) return

    let sessionId
    try {
      sessionId = await ensureSession()
    } catch (err) {
      setAttachmentError(formatApiError(err, '创建会话失败'))
      return
    }

    const userMessage = input.trim()
    const displayMessage = userMessage || '请分析上传的附件。'
    const outgoingAttachments = readyAttachments
    const attachmentIds = outgoingAttachments.map((attachment) => attachment.id)
    setInput('')
    setAttachmentError('')
    setPendingAttachments([])
    setMessages(prev => [...prev, {
      role: 'user',
      content: displayMessage,
      attachments: outgoingAttachments.map((attachment) => ({
        id: attachment.id,
        name: attachment.name,
        contentType: attachment.contentType,
        sizeBytes: attachment.sizeBytes,
        kind: attachment.kind,
      })),
    }])
    setLoading(true)
    setActiveAgentSteps([])

    try {
      const response = await chatApi.streamMessage(
        {
          message: userMessage,
          sessionId,
          useKnowledgeBase,
          useSqlAgent,
          dataSourceConfigId: selectedDataSource || null,
          attachmentIds,
        },
        {
          onStep: (step) => {
            setActiveAgentSteps((previous) => upsertAgentStep(previous, step))
          },
        }
      )

      const assistantMessage = normalizeChatMessage({
        role: 'assistant',
        content: response.reply,
        sqlGenerated: response.sqlGenerated,
        sqlResults: response.sqlResults,
        sqlExecutionSuccess: response.sqlExecutionSuccess,
        sqlErrorMessage: response.sqlErrorMessage,
        errorCode: response.errorCode,
        fromKnowledgeBase: response.fromKnowledgeBase,
        agentSteps: response.agentSteps,
      })
      setMessages(prev => [...prev, assistantMessage])
      loadSessions()
    } catch (err) {
      attachmentIds.forEach((attachmentId) => {
        attachmentApi.delete(attachmentId).catch(() => {
          // 上游失败后尽力清理未绑定的附件。
        })
      })
      setMessages(prev => [...prev, {
        role: 'assistant',
        content: '抱歉，请求失败，请重试。错误：' +
          formatApiError(err, '请求失败')
      }])
    } finally {
      setLoading(false)
      setActiveAgentSteps([])
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
                    系统会生成 SQL，并把执行状态与结果留在同一份记录里。也可以上传
                    表结构截图、CSV 或 Markdown 文件，让模型结合附件继续分析。
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
                  <IconPaperclip size={14} />
                  <span>在下方上传附件，或先设置执行上下文再输入查询。</span>
                </div>
              </section>
            )}

            {messages.map((msg, index) => {
              const isUser = msg.role === 'user'
              return (
                <article key={index} className={`message ${msg.role}`}>
                  {(
                    !isUser ||
                    msg.fromKnowledgeBase ||
                    msg.sqlGenerated ||
                    msg.attachments?.length > 0 ||
                    msg.agentSteps?.length > 0
                  ) && (
                    <div className="message-meta">
                      <span className="message-author">
                        {isUser ? '你' : 'SQL Agent'}
                      </span>
                      {msg.attachments?.length > 0 && (
                        <span className="token">
                          {msg.attachments.length} ATTACHMENT
                        </span>
                      )}
                      {msg.fromKnowledgeBase && <span className="token">RAG</span>}
                      {msg.agentSteps?.length > 0 && (
                        <span className="token">AGENT</span>
                      )}
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
                    {msg.role === 'assistant' && (
                      <AgentWorkflow steps={msg.agentSteps} />
                    )}
                    {msg.attachments?.length > 0 && (
                      <div className="message-attachments">
                        <AttachmentList attachments={msg.attachments} />
                      </div>
                    )}
                    <div className="transcript-copy">
                      <ReactMarkdown components={{ pre: CodeBlock }}>
                        {msg.content}
                      </ReactMarkdown>
                    </div>

                    {msg.role === 'assistant' &&
                      msg.sqlExecutionSuccess === false &&
                      msg.sqlErrorMessage &&
                      !msg.sqlGenerated && (
                        <div className="form-error message-error" role="alert">
                          <span>{msg.sqlErrorMessage}</span>
                        </div>
                      )}

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
                <div className="message-content assistant">
                  <AgentWorkflow steps={activeAgentSteps} live />
                  <div className="loading-status" role="status" aria-live="polite">
                    <span className="signal-line" aria-hidden="true" />
                    <span>
                      {useSqlAgent
                        ? 'Agent 正在调用工具完成任务'
                        : 'Agent 正在分析文档与请求'}
                    </span>
                    <span className="loading-caret" aria-hidden="true">_</span>
                  </div>
                </div>
              </div>
            )}

            <div ref={messagesEndRef} />
          </div>
        </div>

        <div className="chat-footer">
          <div
            className={`composer ${isDraggingFiles ? 'is-dragging' : ''}`}
            onDragEnter={(event) => {
              if (Array.from(event.dataTransfer.types).includes('Files')) {
                setIsDraggingFiles(true)
              }
            }}
            onDragOver={(event) => event.preventDefault()}
            onDragLeave={handleDragLeave}
            onDrop={handleDropFiles}
          >
            {isDraggingFiles && (
              <div className="composer-drop-zone" aria-hidden="true">
                <IconPaperclip size={20} />
                <span>释放文件以上传</span>
              </div>
            )}

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

            {attachmentError && (
              <div className="attachment-error" role="alert">
                {attachmentError}
              </div>
            )}

            {pendingAttachments.length > 0 && (
              <div className="pending-attachments">
                <div className="pending-attachments-head">
                  <span>ATTACHMENTS</span>
                  <span>
                    {readyAttachments.length} / {MAX_ATTACHMENTS} READY
                  </span>
                </div>
                <AttachmentList
                  attachments={pendingAttachments}
                  onRemove={handleRemoveAttachment}
                  disabled={loading}
                />
              </div>
            )}

            <div className="input-row">
              <input
                ref={fileInputRef}
                type="file"
                className="sr-only"
                accept={ATTACHMENT_ACCEPT}
                multiple
                onChange={(event) => {
                  handleFilesSelected(event.target.files)
                  event.target.value = ''
                }}
                tabIndex={-1}
              />
              <button
                type="button"
                className="attach-btn"
                onClick={() => fileInputRef.current?.click()}
                disabled={
                  loading ||
                  isConversationLoading ||
                  isUploadingAttachments ||
                  pendingAttachments.length >= MAX_ATTACHMENTS
                }
                aria-label="上传附件"
                title="上传图片或文本文件"
              >
                {isUploadingAttachments
                  ? <span className="send-progress" aria-hidden="true" />
                  : <IconPaperclip size={17} />}
              </button>
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
                    : isUploadingAttachments
                    ? '附件上传中，可继续输入问题…'
                    : useSqlAgent && !selectedDataSource
                    ? '请先选择数据源…'
                    : useSqlAgent
                      ? '输入自然语言查询，例如：查询所有用户的姓名和邮箱…'
                      : '输入你的问题…'
                }
                rows={1}
                disabled={
                  isConversationLoading ||
                  (useSqlAgent && !selectedDataSource)
                }
              />
              <button
                type="button"
                className="send-btn"
                onClick={handleSend}
                disabled={
                  loading ||
                  isConversationLoading ||
                  isUploadingAttachments ||
                  (!input.trim() && readyAttachments.length === 0) ||
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
              <span>Enter 发送 · Shift + Enter 换行 · 可拖拽附件</span>
              <span>图片/文本 5 MB · 文档 20 MB · 最多 4 个</span>
            </div>
          </div>
        </div>
      </main>
    </div>
  )
}
