import React from 'react'
import ThemeToggle from './ThemeToggle'
import { Logo } from './Icons'

const SPECIMEN_ROWS = [
  {
    index: '01',
    label: 'QUESTION',
    value: '查询本学期每门课程的平均成绩',
  },
  {
    index: '02',
    label: 'SQL',
    value: "SELECT course, AVG(score) FROM grades GROUP BY course;",
    code: true,
  },
  {
    index: '03',
    label: 'RESULT',
    value: '课程 / 平均分 / 样本数',
  },
]

export default function AuthLayout({ children }) {
  return (
    <div className="auth-page">
      <aside className="auth-intro">
        <div className="auth-brand-row">
          <Logo size={26} />
          <div>
            <div className="auth-wordmark">SQL Agent</div>
            <div className="auth-kicker">QUERY WORKSPACE</div>
          </div>
        </div>

        <div className="auth-intro-body">
          <p className="eyebrow">TEXT-TO-SQL / KNOWLEDGE ENHANCED</p>
          <p className="auth-intro-title">
            把问题写成查询，
            <br />
            让数据库给出证据。
          </p>
          <p className="auth-lead">
            面向数据库课程与数据分析的可核对查询工作台。
            从自然语言到 SQL，再到结果记录，每一步都留在同一处。
          </p>

          <div className="query-specimen" aria-hidden="true">
            <div className="specimen-head">
              <span>QUERY_001</span>
              <span>READ ONLY</span>
            </div>
            {SPECIMEN_ROWS.map((row) => (
              <div
                className={`specimen-row ${row.code ? 'is-code' : ''}`}
                key={row.index}
              >
                <span className="specimen-index">{row.index}</span>
                <span className="specimen-label">{row.label}</span>
                <span className="specimen-value">{row.value}</span>
              </div>
            ))}
          </div>
        </div>

        <div className="auth-meta">
          <span>数据库课程 / 数据分析 / 教学辅助</span>
          <span>SQL · RAG · AGENT</span>
        </div>
      </aside>

      <main className="auth-panel">
        <div className="auth-mobile-brand">
          <Logo size={22} />
          <span>SQL Agent</span>
        </div>

        <div className="auth-panel-top">
          <span className="panel-label">ACCESS / 01</span>
          <ThemeToggle className="auth-theme-toggle" />
        </div>

        <div className="auth-form-wrap">{children}</div>

        <div className="auth-panel-foot">
          <span>QUERY LEDGER</span>
          <span>BUILD 2026.10</span>
        </div>
      </main>
    </div>
  )
}
