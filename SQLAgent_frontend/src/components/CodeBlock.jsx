import React, { useEffect, useState } from 'react'
import { IconCheck, IconCopy } from './Icons'

async function writeToClipboard(text) {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return
    } catch {
      // Fall through to the document-based fallback.
    }
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.top = '-9999px'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()

  const copied = document.execCommand('copy')
  document.body.removeChild(textarea)

  if (!copied) {
    throw new Error('Copy command failed')
  }
}

export function CopyButton({ text, className = '' }) {
  const [copied, setCopied] = useState(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (!copied && !failed) return undefined

    const timeoutId = window.setTimeout(() => {
      setCopied(false)
      setFailed(false)
    }, 1800)

    return () => window.clearTimeout(timeoutId)
  }, [copied, failed])

  const handleCopy = async () => {
    if (!text) return

    try {
      await writeToClipboard(text)
      setFailed(false)
      setCopied(true)
    } catch {
      setCopied(false)
      setFailed(true)
    }
  }

  const label = copied ? '已复制' : failed ? '复制失败' : '复制'

  return (
    <button
      type="button"
      className={`copy-button ${copied ? 'is-copied' : ''} ${failed ? 'is-failed' : ''} ${className}`.trim()}
      onClick={handleCopy}
      disabled={!text}
      aria-label={copied ? '代码已复制' : '复制代码'}
    >
      {copied ? <IconCheck size={13} /> : <IconCopy size={13} />}
      <span aria-live="polite">{label}</span>
    </button>
  )
}

function getNodeText(node) {
  if (node === null || node === undefined || typeof node === 'boolean') return ''
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(getNodeText).join('')
  if (React.isValidElement(node)) return getNodeText(node.props.children)
  return ''
}

export default function CodeBlock({ children, node, ...props }) {
  const codeElement = React.Children.toArray(children).find(
    (child) => React.isValidElement(child) && child.type === 'code'
  )
  const rawText = getNodeText(codeElement?.props?.children ?? children)
  const codeText = rawText.replace(/\n$/, '')
  const language = codeElement?.props?.className
    ?.match(/language-([\w-]+)/)?.[1]

  return (
    <div className="markdown-code">
      <div className="markdown-code-toolbar">
        <span className="markdown-code-language">
          {language ? language.toUpperCase() : 'CODE'}
        </span>
        <CopyButton text={codeText} />
      </div>
      <pre {...props}>{children}</pre>
    </div>
  )
}
