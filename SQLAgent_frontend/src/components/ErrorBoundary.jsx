import React from 'react'

export default class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props)
    this.state = { hasError: false }
  }

  static getDerivedStateFromError() {
    return { hasError: true }
  }

  componentDidCatch(error, errorInfo) {
    console.error('未捕获的前端渲染错误:', error, errorInfo)
  }

  handleReload = () => {
    window.location.reload()
  }

  render() {
    if (this.state.hasError) {
      return (
        <main className="error-boundary" role="alert">
          <div className="error-boundary-panel">
            <p className="eyebrow">RENDER ERROR / RECOVERY</p>
            <h1>界面加载失败</h1>
            <p>
              当前页面遇到未处理的渲染错误。你可以重新加载页面继续使用，
              已经保存的会话数据不会因此被删除。
            </p>
            <div className="error-boundary-actions">
              <button type="button" className="btn btn-primary" onClick={this.handleReload}>
                重新加载
              </button>
            </div>
          </div>
        </main>
      )
    }

    return this.props.children
  }
}
