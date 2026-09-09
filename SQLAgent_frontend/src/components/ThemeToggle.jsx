import React from 'react'
import { useTheme } from '../theme/ThemeContext'
import { IconSun, IconMoon } from './Icons'

export default function ThemeToggle({ className = '' }) {
  const { theme, toggleTheme } = useTheme()
  const isDark = theme === 'dark'

  return (
    <button
      type="button"
      className={`theme-toggle ${className}`}
      onClick={toggleTheme}
      title={isDark ? '切换到浅色模式' : '切换到深色模式'}
      aria-label="切换主题"
    >
      {isDark ? <IconSun size={17} /> : <IconMoon size={17} />}
    </button>
  )
}
