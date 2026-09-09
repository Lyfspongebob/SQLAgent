import React from 'react'
import ThemeToggle from './ThemeToggle'
import { Logo, IconSparkles, IconCode, IconDatabase } from './Icons'

const FEATURES = [
  {
    icon: IconSparkles,
    title: '自然语言转 SQL',
    desc: '用一句话描述需求，自动生成可执行的 SQL 语句',
  },
  {
    icon: IconCode,
    title: '智能生成与优化',
    desc: '结合知识库与 SQL Agent，输出更准确、更高效',
  },
  {
    icon: IconDatabase,
    title: '多数据库支持',
    desc: '兼容 MySQL、PostgreSQL、SQL Server',
  },
]

export default function AuthLayout({ children }) {
  return (
    <div className="auth-page">
      {/* 左侧品牌介绍区 */}
      <aside className="auth-hero">
        <div className="hero-top">
          <div className="hero-brand">
            <Logo size={40} />
            <span className="hero-brand-name">SQL Agent</span>
          </div>
        </div>

        <div className="hero-body">
          <h1 className="hero-title">
            用自然语言，
            <br />
            查询你的数据库
          </h1>
          <p className="hero-subtitle">
            SQL Agent 将自然语言转换为精准的 SQL，
            支持多种数据库，帮助团队高效获取数据洞察。
          </p>

          <div className="hero-demo">
            <span className="demo-dot demo-dot-r" />
            <span className="demo-dot demo-dot-y" />
            <span className="demo-dot demo-dot-g" />
            <span className="demo-text">SELECT name, email FROM users WHERE city = '深圳'</span>
          </div>

          <div className="hero-features">
            {FEATURES.map((f) => (
              <div className="feature" key={f.title}>
                <span className="feature-icon">
                  <f.icon size={20} />
                </span>
                <div>
                  <div className="feature-title">{f.title}</div>
                  <div className="feature-desc">{f.desc}</div>
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="hero-foot">
          <span>高效</span>
          <span>安全</span>
          <span>智能</span>
        </div>
      </aside>

      {/* 右侧表单区 */}
      <main className="auth-panel">
        <ThemeToggle className="auth-theme-toggle" />
        <div className="auth-card">{children}</div>
      </main>
    </div>
  )
}
