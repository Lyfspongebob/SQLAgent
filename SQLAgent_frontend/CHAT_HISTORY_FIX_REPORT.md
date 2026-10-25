# 会话切换崩溃修复技术报告

日期：2026-10-19  
修复分支：`fix/chat-history-contract`  
前端基线提交：`0e63b76`  
备份分支：`backup/pre-chat-history-fix-20261019`

## 1. 摘要

本次修复解决了从一条会话切换到另一条会话时，聊天页面因历史消息中的 SQL 结果数据类型不一致而崩溃、刷新后白屏的问题。

问题根因不是 UI 样式，而是同一个 `sqlResults` 字段在不同接口中代表不同数据类型：

- 实时发送接口返回数组；
- 数据库实体使用字符串；
- 保存历史时使用 Java `List.toString()`，产生的是 Java 调试文本，不是 JSON；
- 历史接口直接返回实体，前端仍按数组调用 `.map()`；
- React 渲染异常没有 Error Boundary 捕获，最终导致整个聊天页面被卸载。

本次采用的前端与后端组合方案具备以下特点：

- 不改数据库表结构；
- 不做破坏性数据迁移；
- 新数据使用合法 JSON 保存；
- 旧数据继续可读，解析失败时退化为原始文本；
- 历史接口不再暴露 JPA 实体；
- 增加会话请求取消、加载失败重试和全局错误恢复；
- 新旧数据、旧格式字符串、null 和异常数据均不会再次导致整页白屏。

## 2. 问题复现与根因

### 2.1 崩溃日志

浏览器控制台报错：

```text
Uncaught TypeError: msg.sqlResults.map is not a function
```

异常发生在会话历史消息的结果表格渲染阶段。

### 2.2 原有数据链路

原有链路如下：

```text
SQL 执行结果 List<Map<String, Object>>
  -> ChatResponse.sqlResults（数组）
  -> ChatService 使用 response.getSqlResults().toString()
  -> ChatMessage.sqlResults（TEXT 字符串）
  -> GET /api/chat/history/{sessionId} 直接返回 ChatMessage
  -> 前端收到字符串
  -> 判断 length > 0 后调用 msg.sqlResults.map()
  -> TypeError
  -> Chat 组件被卸载
  -> 页面白屏
```

Java `List<Map>.toString()` 的典型结果类似：

```text
[{id=1, name=Alice}]
```

它是 Java 调试文本，不是 JSON：

- 键和值之间是 `=`，不是 `:`；
- 字符串值没有稳定转义；
- 值中包含逗号、等号、花括号时可能无法可靠还原；
- 不能直接交给 `JSON.parse()` 或前端数组 API 使用。

### 2.3 为什么刷新后会闪一下再消失

页面首次渲染时消息列表为空，会短暂显示空状态。随后自动选择第一条会话并请求历史记录；历史数据返回后触发异常，整个 `Chat` 被 React 卸载，因此用户看到的是“闪一下后消失”。

### 2.4 字段命名不一致

历史实体使用：

- `sqlSuccess`

实时响应和前端使用：

- `sqlExecutionSuccess`

因此即使结果本身可以解析，切换历史会话后执行成功状态也会丢失。

## 3. 修复范围与约束

本次修改遵循以下约束：

- 不修改依赖文件；
- 不修改 Vite 配置；
- 不修改数据库表结构；
- 不删除任何历史消息；
- 不覆盖旧 `sql_results` 数据；
- 另外发现的安全和架构问题留到后续任务。

## 4. 修改内容

### 4.1 前端消息归一化

新增：

```text
SQLAgent_frontend/src/utils/chatMessage.js
```

职责：

1. 统一实时响应与历史响应的消息结构；
2. 将 `sqlSuccess` 映射为 `sqlExecutionSuccess`；
3. 支持数组结果；
4. 支持合法 JSON 字符串；
5. 兼容常见的 Java `List/Map.toString()` 历史格式；
6. 无法解析时保留原始文本，供界面降级展示；
7. 限制旧格式原文展示长度，避免异常大文本拖垮页面。

兼容策略：

- 优先使用结构化 `sqlResults`；
- 如果后端返回 `sqlResultText`，继续尝试解析旧格式；
- 解析成功时显示表格，并标记为 `LEGACY`；
- 解析失败时显示 `LEGACY RESULT / RAW TEXT` 面板；
- 绝不再对未知类型直接调用 `.map()`。

### 4.2 会话切换与请求竞态处理

修改：

```text
SQLAgent_frontend/src/views/Chat.jsx
SQLAgent_frontend/src/api/chat.js
```

主要变化：

- 切换会话时立即清空旧消息；
- 使用 `AbortController` 取消上一条历史请求；
- 使用请求序号忽略迟到响应，避免旧会话覆盖新会话；
- 历史加载期间显示明确加载状态；
- 历史加载失败时显示错误和“重试”按钮；
- 历史加载期间禁止发送，避免历史响应覆盖新消息；
- 初次加载会话列表时不再短暂闪现空状态；
- 发送成功后和历史加载后都经过同一个消息归一化函数。

这解决了另一类潜在问题：用户快速连续切换会话时，慢请求可能在新请求之后返回，导致消息显示在错误会话下。

### 4.3 旧格式结果降级展示

当后端返回的旧结果无法解析为表格时，页面显示原始结果文本，而不是崩溃。

新增样式：

- `history-status`
- `history-error`
- `legacy-result-text`

### 4.4 全局 Error Boundary

新增：

```text
SQLAgent_frontend/src/components/ErrorBoundary.jsx
```

并在 `main.jsx` 包裹整个应用。

作用：

- 单个组件或消息渲染失败时，不再导致整个应用直接空白；
- 显示可恢复的错误页面；
- 提供“重新加载”按钮；
- 在开发阶段继续输出详细错误，便于定位。

Error Boundary 是最后一道防线，不替代数据归一化和接口契约修复。

### 4.5 后端历史结果编解码

新增：

```text
SQLAgent/src/main/java/com/example/sqlagent/chat/ChatResultCodec.java
```

职责：

- 写入时使用 Jackson 将 `List<Map<String, Object>>` 序列化为合法 JSON；
- 读取时优先按 JSON 反序列化；
- 无法按 JSON 解析时保留为 `legacy_text`；
- 对 JDBC 返回的二进制和特殊类型做 JSON 安全化处理；
- 序列化失败时记录告警，不阻塞聊天主流程。

特殊类型处理：

- `byte[]` 转为二进制长度说明；
- `Blob` 转为 Blob 长度说明；
- `Clob` 读取有限长度文本；
- SQL Array 递归处理；
- Map、Iterable 递归处理；
- 时间、日期、UUID 转为字符串；
- 其他未知对象转为字符串。

### 4.6 历史响应 DTO

新增：

```text
SQLAgent/src/main/java/com/example/sqlagent/dto/ChatMessageResponse.java
```

历史接口不再直接返回 JPA 实体，而是返回稳定 DTO：

- `id`
- `sessionId`
- `role`
- `content`
- `sqlGenerated`
- `sqlResults`
- `sqlResultText`
- `sqlResultFormat`
- `sqlExecutionSuccess`
- `fromKnowledgeBase`
- `createTime`

其中：

- 新数据返回 `sqlResults` 数组和 `sqlResultFormat=json`；
- 旧数据返回 `sqlResults=null`、`sqlResultText=旧文本`、`sqlResultFormat=legacy_text`；
- `sqlSuccess` 在服务层映射为与实时响应一致的 `sqlExecutionSuccess`。

### 4.7 保存与历史读取

修改：

```text
SQLAgent/src/main/java/com/example/sqlagent/chat/ChatService.java
SQLAgent/src/main/java/com/example/sqlagent/chat/ChatController.java
```

保存新消息时：

```text
List<Map<String, Object>>
  -> ChatResultCodec.serialize()
  -> 合法 JSON
  -> ChatMessage.sqlResults（原 TEXT 字段）
```

读取历史时：

```text
ChatMessage.sqlResults
  -> ChatResultCodec.deserialize()
  -> ChatMessageResponse
  -> 前端 normalizeChatMessage()
  -> 表格或原始文本降级
```

### 4.8 测试

新增前端归一化测试：

```text
SQLAgent_frontend/src/utils/chatMessage.test.mjs
```

覆盖：

- 原生数组；
- JSON 字符串；
- Java List/Map 文本；
- 无法解析的原始文本；
- 历史 DTO 的 `sqlResultText`；
- `sqlSuccess` 到 `sqlExecutionSuccess` 的映射；
- null 结果。

新增后端测试：

```text
SQLAgent/src/test/java/com/example/sqlagent/chat/ChatResultCodecTest.java
```

覆盖：

- 结构化结果 JSON 往返；
- 旧格式文本降级；
- 二进制值安全化；
- 空结果处理。

## 5. 为什么没有直接修改数据库结构

没有把 `ChatMessage.sqlResults` 直接改成 `List`，也没有给旧字段加严格的 JSON AttributeConverter。

原因：

1. 旧数据不是合法 JSON；
2. 严格转换器会在读取实体时抛错；
3. 一旦实体加载失败，不仅历史接口不可用，会话删除等依赖实体读取的功能也可能一起失效；
4. 当前项目使用 `ddl-auto: update`，不适合承担不可逆的字段类型迁移。

因此本次保持：

- 数据库字段仍为 `TEXT`；
- 实体字段仍为 `String`；
- 只在服务层和 DTO 层处理类型转换。

这是一种低风险、可回退的过渡方案。后续如果项目需要更强的查询能力和数据治理，可以新增原生 JSON 列并引入 Flyway/Liquibase，而不是原地修改旧列。

## 6. 修复后收益

1. 切换历史会话不再因为 `.map()` 抛错而白屏。
2. 刷新页面可以正常进入已有会话。
3. 新 SQL 结果可以稳定恢复为结构化表格。
4. 常见旧格式结果可以继续显示为表格。
5. 无法解析的旧结果仍有原始文本可查看。
6. 历史会话的执行成功状态可以恢复。
7. 快速切换会话不会显示错会话消息。
8. 历史加载失败有可见错误和重试入口。
9. 单条消息异常不会再拖垮整个应用。
10. 新旧版本可以并行过渡，不需要一次性清洗数据库。

## 7. 兼容性和限制

### 7.1 旧格式解析是尽力而为

Java `List/Map.toString()` 不是为机器解析设计的。以下情况可能导致字段解析不准确：

- 字符串值中包含逗号；
- 字符串值中包含等号；
- 字符串值或嵌套对象格式复杂；
- 值本身包含未转义的花括号。

当前策略是尽量显示，失败时退化为原始文本，不会崩溃，也不会删除数据。

### 7.2 尚未持久化 SQL 错误详情

数据库实体目前没有 `sqlErrorMessage` 字段。失败 SQL 的详细错误仍主要保留在助手回复正文中。

如果后续需要历史页完整恢复错误面板，应增加错误信息持久化字段。

### 7.3 尚未限制 SQL 结果体积

当前执行层仍可能返回非常大的结果集。本次修复解决了类型崩溃和页面恢复问题，但结果行数、单元大小和总 JSON 大小上限应作为下一项数据治理任务处理。

### 7.4 尚未迁移为 MySQL 原生 JSON

当前合法 JSON 仍保存在 `TEXT` 字段中。对当前项目规模足够，但长期应新增 JSON 列并配合数据库迁移工具。

## 8. 验证结果

已执行：

```text
node src/utils/chatMessage.test.mjs
```

结果：通过。

已执行：

```text
npm run build -- --outDir .ui-build-check --emptyOutDir
```

结果：Vite 生产构建通过。

已执行：

```text
.\gradlew.bat test --tests com.example.sqlagent.chat.ChatResultCodecTest --no-daemon
```

结果：通过。

已执行：

```text
.\gradlew.bat test --no-daemon
```

结果：完整后端测试通过。

## 9. 回退方案

本次没有数据库结构变更，因此回退只需要切回代码分支或 revert 修复提交，不需要回滚数据库。

可用回退点：

- 前端基线提交：`0e63b76`
- 备份分支：`backup/pre-chat-history-fix-20261019`
- 修复分支：`fix/chat-history-contract`

推荐回退方式：

```text
git switch backup/pre-chat-history-fix-20261019
```

或在修复分支上单独撤销后端修复提交：

```text
git revert <后端修复提交哈希>
```

注意：不要使用 `git reset --hard`，除非明确确认不需要保留当前工作区内容。

## 10. 后续单独处理的问题

以下问题已识别，但不混入本次修复：

1. SQL 执行结果行数、单元大小和总 JSON 大小上限。
2. 持久化 `sqlErrorMessage`。
3. 新增原生 JSON 列并引入数据库迁移工具。
4. 数据源查询、修改和删除的用户归属校验。
5. `application.yml` 中的明文密钥外置。
6. 将 `node_modules` 和 Vite 缓存从版本控制中清理。
