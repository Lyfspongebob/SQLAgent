---
name: SQLAgent 前端重设计
overview: 将 SQLAgent 前端从紫色渐变+emoji 的典型"AI味"风格，重设计为「浅色/深色可切换」的专业简洁风格（默认浅色），覆盖登录、注册、聊天、数据源配置四个页面，替换 emoji 为 SVG 图标，统一 CSS 变量主题体系与柔和背景。
design:
  architecture:
    framework: react
  styleKeywords:
    - 专业工具型
    - 克制
    - 浅色与深色主题
    - 柔和渐变背景
    - 蓝色主色
    - SVG 线型图标
    - 清晰层级
  fontSystem:
    fontFamily: PingFang SC
    heading:
      size: 24px
      weight: 600
    subheading:
      size: 16px
      weight: 500
    body:
      size: 14px
      weight: 400
  colorSystem:
    primary:
      - "#2563EB"
      - "#3B82F6"
      - "#1D4ED8"
    background:
      - "#F7F8FA"
      - "#FFFFFF"
      - "#0F172A"
      - "#1E293B"
    text:
      - "#111827"
      - "#6B7280"
      - "#E5E7EB"
      - "#94A3B8"
    functional:
      - "#16A34A"
      - "#DC2626"
      - "#D97706"
      - "#2563EB"
todos:
  - id: theme-foundation
    content: 建立主题系统：新增 ThemeContext、ThemeToggle 与 Icons 组件，重写 global.css 主题变量与基础样式，并在 main.jsx/App.jsx 接入
    status: completed
  - id: auth-pages
    content: 重设计登录与注册页面，替换 emoji 为 SVG 图标并接入主题切换
    status: completed
    dependencies:
      - theme-foundation
  - id: chat-page
    content: 重设计聊天页面侧边栏、消息气泡、SQL 代码块与输入区，抽离内联样式
    status: completed
    dependencies:
      - theme-foundation
  - id: datasource-page
    content: 重设计数据源配置页表单、列表与操作按钮，抽离内联样式
    status: completed
    dependencies:
      - theme-foundation
  - id: verify-build
    content: 运行 npm run build 验证构建，并检查浅色/深色切换与各页面交互无回归
    status: completed
    dependencies:
      - auth-pages
      - chat-page
      - datasource-page
---

## 用户需求

将 SQLAgent 前端从当前"紫色渐变 + emoji 图标"的典型 AI 风格，整体重设计为**浅色 + 深色可切换**的专业视觉风格，默认浅色，覆盖登录、注册、聊天、数据源配置四个页面。

## 产品概述

一款用于自然语言查询数据库的 SQL 智能助手的 Web 前端。重设计后应呈现简洁、克制、耐看的专业工具质感：浅色模式下干净明亮，深色模式下沉稳低干扰，并允许用户一键切换主题。

## 核心功能

- 全局浅色 / 深色主题切换，主题偏好持久化保存，刷新后保持
- 登录、注册页面的品牌化表单设计，含统一主题切换入口
- 聊天主界面重设计：侧边栏会话列表、消息气泡、Markdown / SQL 代码块 / 结果表格、输入区与功能开关
- 数据源配置页重设计：配置表单、数据源列表、编辑 / 删除 / 测试连接操作
- 统一图标体系：移除 emoji，改用风格一致的 SVG 图标
- 柔和背景：浅色模式使用淡蓝 / 青色渐变网格，深色模式使用深蓝底 + 微弱径向光晕

## 约束

- 不修改后端 API、请求逻辑与页面交互行为，仅做视觉层重构
- 保持现有路由、认证、会话、数据源等功能完全可用

## 技术栈

- 前端框架：React 19 + Vite 5 + react-router-dom v7（沿用现有项目）
- 样式方案：纯手写 CSS，采用 CSS 变量（Custom Properties）实现主题系统，不引入 UI 组件库和额外运行时依赖
- 图标方案：内联 SVG 组件，统一线型风格，零额外依赖
- 状态管理：React Context + localStorage 管理主题状态

## 实现方案

采用"主题变量 + 主题上下文 + 组件重构"的策略：在根元素 `document.documentElement` 上通过 `data-theme` 属性切换 `:root` 与 `[data-theme="dark"]` 两套 CSS 变量，所有组件颜色、背景、边框、阴影均引用变量，实现全局一键换肤。仅改动视觉层，现有数据请求与交互逻辑保持不动。

## 架构设计

```
ThemeProvider (React Context)
  └── 提供 theme / toggleTheme
  └── useEffect 同步 document.documentElement.dataset.theme 与 localStorage
        │
        ├── Login.jsx / Register.jsx（品牌表单 + ThemeToggle）
        ├── Chat.jsx（侧边栏 / 消息区 / 输入区）
        └── DataSourceConfig.jsx（表单 + 列表）
```

### 模块划分

- **主题模块**：`ThemeContext.jsx` 负责主题状态、系统偏好读取、localStorage 持久化与 DOM 属性同步
- **图标模块**：`Icons.jsx` 统一导出新建、数据源、对话、知识库、数据库、太阳、月亮、发送、编辑、删除、连接等 SVG 图标
- **主题切换组件**：`ThemeToggle.jsx` 复用图标模块，供四个页面统一调用
- **样式层**：`global.css` 用 CSS 变量定义两套主题，并重写各页面类名样式

## 实现要点

- 主色由紫色渐变改为克制的蓝色系（`#2563eb` / `#3b82f6`），浅色底用 `#f7f8fa` 系，深色底用 `#0f172a` 系，中性灰阶负责边框与次要文字
- 深色模式下单独适配 SQL 代码块、Markdown、结果表格与消息气泡，避免硬编码颜色
- 消除 `Chat.jsx`、`DataSourceConfig.jsx` 中的内联 `style`，全部抽成 CSS 类，确保主题变量覆盖到位
- 主题上下文只暴露 `theme` 与 `toggleTheme`，切换时仅更新根元素属性，不触发大规模重渲染，性能开销可忽略
- 输入框、按钮、卡片、开关胶囊统一圆角、边框、焦点态与 hover 态，保持视觉一致性

## 目录结构

```
SQLAgent_frontend/
├── index.html                      # [MODIFY] 可选：更新标题与 SVG favicon
├── src/
│   ├── main.jsx                    # [MODIFY] 用 ThemeProvider 包裹 App
│   ├── App.jsx                     # [MODIFY] 保持路由与认证逻辑，必要时接入主题切换入口
│   ├── theme/
│   │   └── ThemeContext.jsx        # [NEW] 主题上下文：状态、localStorage、data-theme 同步
│   ├── components/
│   │   ├── Icons.jsx               # [NEW] 统一 SVG 图标集合
│   │   └── ThemeToggle.jsx         # [NEW] 太阳/月亮主题切换按钮
│   ├── views/
│   │   ├── Login.jsx               # [MODIFY] 品牌化登录页，替换 emoji，接入主题切换
│   │   ├── Register.jsx            # [MODIFY] 品牌化注册页，替换 emoji，接入主题切换
│   │   ├── Chat.jsx                # [MODIFY] 重设计侧边栏/消息/SQL 展示/输入区，抽离内联样式
│   │   └── DataSourceConfig.jsx    # [MODIFY] 重设计表单/列表/操作按钮，抽离内联样式
│   └── styles/
│       └── global.css              # [MODIFY] 重写为 CSS 变量主题 + 全部页面样式
```

## 设计风格

采用"专业工具型"设计语言，摆脱紫色渐变与 emoji 的 AI 味，营造克制、干净、耐看的产品质感。浅色模式以白与浅灰蓝为基底，搭配克制的蓝色主色与非常淡的青色径向渐变背景；深色模式以深蓝灰为基底，叠加微弱光晕，降低长时间使用的视觉疲劳。

## 页面设计

### 登录 / 注册页
- 整页居中布局，背景为柔和的径向渐变网格，卡片使用纯色表面 + 细腻描边 + 轻阴影
- 顶部品牌区：统一 Logo 图形 + "SQL Agent" 标题与副标题
- 表单：清晰的 label、统一圆角输入框、聚焦态蓝色描边、主按钮与次级链接层次分明
- 右上角固定主题切换按钮

### 聊天主界面
- 左侧固定侧边栏：品牌区、新建对话按钮、会话列表（选中态用浅蓝底 + 左侧蓝色指示条）、底部数据源入口与用户信息
- 主消息区：用户气泡靠右使用主色填充，助手气泡靠左使用中性表面；Markdown 排版优化，SQL 代码块采用深色代码底，结果表格使用细分隔线与 hover 高亮
- 输入区：功能开关改为胶囊样式，数据源下拉、文本框与发送按钮统一风格，输入区浮于内容上方并有清晰边界

### 数据源配置页
- 与聊天页共用侧边栏，保证跨页一致性
- 主内容区：页头标题与操作按钮、远程连接提示卡片、配置表单卡片、数据源列表卡片
- 列表项 hover 高亮，编辑 / 删除按钮使用浅色语义色，测试连接与保存按钮层次清晰

## 交互与响应

- 主题切换平滑过渡，切换按钮含 hover 与焦点态
- 按钮、输入框、会话项、菜单项均有明确 hover / active 反馈
- 布局在窄屏下保持可用，侧边栏与主区域自适应
