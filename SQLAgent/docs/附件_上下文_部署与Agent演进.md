# 附件、上下文、部署与 Agent 演进方案

## 1. 当前已实现的附件链路

当前 MVP 已支持：

- 图片：PNG、JPEG、GIF。
- 文档：PDF、DOCX/DOC、XLSX/XLS。
- 文本文件：TXT、Markdown、CSV、JSON、JSONL、SQL、LOG、YAML、XML。
- 图片/文本单个最大 5 MB，文档单个最大 20 MB。
- 每条消息最多 4 个附件，全部原始附件总计最大 30 MB，图片送入模型总计最大 12 MB。
- 文本文件按 UTF-8 读取，单文件最多注入 20,000 字符，单条消息最多 40,000 字符。
- PDF、Word、Excel 上传时生成结构清单和解析产物，发送时按用户问题选择相关页、章节和数据行。
- 文档上下文默认每条消息最多 16,000 字符，每份文档最多 10,000 字符。
- 文档解析单元持久化到 `document_chunks`，可在整个会话中检索。
- 默认对话由 `AgentRunner` 驱动，模型通过工具读取文档、检查 schema、校验和执行 SQL。
- 前端通过 SSE 展示可折叠 Agent 工作流，工作流同时写入消息历史。
- 图片以 OpenAI 兼容的多模态消息格式发送：

```json
{
  "type": "image_url",
  "image_url": {
    "url": "data:image/png;base64,..."
  }
}
```

- 文本附件放在带 `<attachment>` 边界的不可信上下文中。
- 附件按用户、会话和消息隔离，会话删除时同步删除附件。
- 当前开发环境使用本地磁盘；`AttachmentStorage` 是存储抽象，生产环境可替换成 OSS。

当前限制：

- 图片 OCR 尚未实现。
- 扫描 PDF 目前只标记疑似扫描页，尚未渲染页面或 OCR；复杂跨页表格和图表未完整还原。
- 旧版 DOC/XLS 已有解析代码，仍需真实文件验收。
- 文档和文本附件可以贯穿会话；历史图片尚未提供跨消息按需视觉读取。
- 文档检索目前是关键词和定位匹配，尚未接入向量语义检索。
- 未做 EXIF 清除、病毒扫描、按用户配额、定时清理孤儿附件。
- MySQL 表结构目前仍由 Hibernate 更新；生产环境应改用 Flyway 或 Liquibase。
- 文档解析目前在请求线程中同步执行，生产环境应迁移到异步 Worker。

## 2. 上下文管理

### 2.1 当前真实状态

`chat_messages` 已经持久化历史消息，前端也能读取历史；但是当前模型调用只使用本次用户问题。

- 普通对话调用 `normalChat(userMessage, null)`。
- SQL Agent 只使用当前问题、数据库 schema、知识库召回结果和工具执行结果。
- 前一轮的图片、文本附件、SQL 错误和查询结果，不会自动进入下一轮。

因此，目前的历史记录主要是“界面历史”，还不是“模型上下文”。

### 2.2 建议的上下文分层

建议新增 `ConversationContextBuilder`，按优先级组装：

1. 系统规则：身份、只读边界、附件不可信说明、输出约束。
2. 用户与租户上下文：用户、数据源、权限、当前教学模式。
3. 长期摘要：较早对话的滚动摘要。
4. 近期原文：最近若干轮用户与助手消息。
5. 工具观察：schema、SQL、执行结果、错误和修复记录。
6. 附件摘要：图片的视觉摘要、文本摘要、表结构识别结果和原始对象引用。

不要每轮都重新发送完整图片和完整数据库结果。推荐：

- 附件首次进入时生成可审计的结构化摘要。
- 后续轮次优先注入摘要，确有必要时按对象引用重新读取原图。
- SQL 结果只保留列、行数、统计摘要和前若干行，避免撑爆 token。
- 为不同模型配置独立的 token 预算。
- 超过预算后先生成滚动摘要，再裁掉最老的原始消息。
- 在数据库保存上下文快照版本，方便复现和评测。

### 2.3 建议的数据结构

可以增加三类记录：

- `conversation_summaries`：会话级滚动摘要。
- `message_artifacts`：消息产生的结构化产物，例如 schema、SQL、结果摘要、附件摘要。
- `model_traces`：每次模型调用的模型、上下文版本、工具轨迹、耗时和 token 用量。

不建议把大段 SQL 结果和高分辨率图片直接塞进聊天消息的 `TEXT` 字段。

## 3. 阿里云部署与附件存储

### 3.1 推荐架构

```text
Browser
  | HTTPS
SLB / Nginx -> Spring Boot on ECS
  |                  |          |
RDS MySQL         Redis       OSS private bucket
  |                             |
metadata                   image / document objects
```

推荐组件：

- 应用：ECS + Nginx/SLB + HTTPS。
- 关系数据：RDS MySQL，放在私有 VPC，禁止公网访问。
- 附件：阿里云 OSS 私有 Bucket。
- 会话、限流、分布式锁、任务状态：Redis。
- 异步解析：Redis Stream、RocketMQ 或自建解析 Worker。
- 日志与监控：SLS + 云监控。

### 3.2 上传方式

开发期可以继续使用 `POST /api/attachments` 由应用服务器中转。

生产期推荐：

1. 浏览器请求应用获取 STS 临时凭证或预签名上传参数。
2. 浏览器直接上传到 OSS，应用服务器不承载大文件流量。
3. 浏览器调用确认接口，应用校验对象大小、类型和哈希。
4. 应用把元数据写入 RDS。
5. 解析 Worker 异步生成文本、缩略图、摘要和恶意文件扫描结果。

对象键建议：

```text
users/{userId}/sessions/{sessionId}/{attachmentId}/{uuid}.{ext}
```

安全要求：

- Bucket 禁止公共读，使用短期签名 URL。
- 下载接口必须校验用户和会话所有权。
- 限制 MIME、扩展名、文件头、大小和图片像素。
- 对 PDF/Office 使用隔离 Worker 解析，防止压缩炸弹和宏。
- 清除图片 EXIF，避免泄露设备定位等元数据。
- 设置生命周期规则，自动删除 24 小时未绑定的临时附件。
- 对用户设置日上传额度、总容量和请求速率限制。
- 如模型支持私有 URL，可直接传短期签名 URL；否则由后端读取对象并转成 base64。

## 4. 用户本地数据库

### 4.1 为什么浏览器不能直接连接

浏览器网页无法安全地直接连接用户的 MySQL/PostgreSQL：

- 数据库使用 TCP 私有协议，不是浏览器可用的 HTTP API。
- CORS 不能解决数据库协议问题。
- 把数据库账号密码交给云端网页会带来严重泄露风险。
- 直接开放公网端口、使用 frp/ngrok 暴露数据库，风险远高于教学收益。

### 4.2 推荐：控制面在云、数据面在本地

为教学场景提供一个轻量“本地数据库连接器”：

```text
Cloud web app
  | authenticated outbound WebSocket
Local Connector
  | localhost / private network
User database
```

连接器职责：

- 安装在用户电脑，由用户主动启动。
- 只主动向外建立 WSS 长连接，不开放公网端口。
- 数据库密码保存在本机，优先使用操作系统密钥链。
- 使用一次性配对码绑定当前网页登录和本机会话。
- 接收云端下发的任务 token，只允许当前用户、当前会话、有限时长。
- 只执行参数化、只读、超时和限行查询。
- 在本地二次校验 SQL，并保留执行审计。
- 结果默认脱敏、截断后再返回云端。

云端职责：

- 用户认证、数据源归属、SQL 生成、策略和审计。
- 通过工具协议调用本地连接器，而不是让云端主动访问用户内网。
- 连接器离线时给出明确状态，不假装查询成功。

同时保留“云端教学数据集”作为默认路径。这样普通用户不需要安装连接器，也能完成教学任务；只有确实要分析本机数据的高级用户安装连接器。

## 5. Agent 演进

### 5.1 当前实现

默认聊天已经切换到 `AgentRunner` 工具循环：

- 模型自主选择文档、数据库、SQL 校验和知识库工具。
- `validate_sql` 允许分类 INSERT/UPDATE/DELETE，但不会执行。
- `execute_readonly_sql` 只接受单条 SELECT。
- 工具调用失败会作为观察结果回填模型，不会直接丢失最终回答。
- SSE 将每个工具步骤推送到前端，并保存到消息历史。

`SQLAgentService2` 保留为旧版候选投票和共识流程，用于对比实验与兼容测试，不再是默认聊天链路。

### 5.2 工具接口

当前统一工具接口：

```java
public interface AgentTool {
    String name();
    String description();
    ObjectNode parameters(ObjectMapper mapper);
    AgentToolResult execute(AgentToolContext context, JsonNode arguments);
}
```

已实现工具：

- `list_session_documents`：列出会话文档。
- `get_session_document_manifest`：查看文件格式和结构。
- `search_session_documents`：检索相关页、章节和表格块。
- `read_document_chunks`：读取完整文档片段。
- `inspect_database_schema`：获取表、列和类型。
- `validate_sql`：分类 SQL，不执行。
- `execute_readonly_sql`：只执行单条 SELECT。
- `search_knowledge_base`：检索应用知识库。

工具循环：

```text
理解目标 -> 选择工具 -> 执行 -> 观察结果
          ^                     |
          +------ 继续或结束 ----+
```

当前限制与后续增强：

- 最大工具步数。
- 单步超时和全局超时。
- 工具参数 JSON Schema。
- 写操作目前不允许执行。
- 每步 trace 和费用统计。
- 无进展检测，避免循环。
- 失败后的降级路径。

Spring AI 和 LangChain4j 都可以使用，但当前项目已经有稳定的模型调用、SQL 校验和执行逻辑，直接整体迁移框架会增加风险。更稳妥的路线是先抽出 `AgentTool`、`AgentState`、`AgentRunner`，再评估框架是否值得替换基础设施。

### 5.3 评估

Agent 化以后不能只看“回答像不像”，需要建立评测集：

- SQL 正确率。
- 只读安全拦截率。
- 工具选择正确率。
- 平均步数、延迟和 token 成本。
- 附件理解准确率。
- 本地连接器的越权测试。

每个版本保存评测结果，才能判断“更像 Agent”是否真的带来效果提升。

## 6. 建议实施顺序

1. 已上线 PDF/Office 解析、会话级 chunk 索引、工具循环和前端 trace。
2. 已完成 8 条近期消息窗口，后续实现正式 token 预算和滚动摘要。
3. 已实现 schema、SQL、文档和知识库工具。
4. 已实现 SSE 工具 trace；后续补费用、无进展检测和取消。
5. 实现本地数据库连接器与云端配对协议。
6. 建立评测集和可回放测试。
