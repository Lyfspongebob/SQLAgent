# 智谱知识库 Java 调用实战：从 retrieval 失效到可观测的 RAG

> 适用于 Java / Spring Boot 开发者，以及需要在自己项目中接入智谱知识库、但又不想额外部署 Python 服务的同学。
>
> CSDN 标签：Java、Spring Boot、RAG、智谱 AI、知识库

## 摘要

本文记录一次 Spring Boot 项目接入智谱知识库的真实排错过程：请求参数看起来都正确，知识库文档也完成了向量化，但 `tools.retrieval` 返回的却是模型自身的通用回答。最终通过“显式检索 + 受约束生成”的两阶段 RAG 方案解决问题，并补齐了 Java 直接调用知识库 HTTP 接口的实现。

文章包含：

- 旧方案为什么不能证明 RAG 成功；
- 智谱知识库 Java 调用方法；
- 片段召回、上下文拼接和回答生成；
- 知识库模式与 SQL Agent 模式的 AI 分工；
- 配置参数、性能、安全与排错清单。

> 文中的 API Key、知识库 ID、业务数据和示例人物均为占位或虚构内容。

## 一、问题背景

项目是一个基于 Spring Boot 的智能 SQL 助手，前端提供了一个“知识库”开关。开启后，用户问题应该先检索知识库，再由模型基于检索结果回答。

理想链路是：

```text
用户问题
  -> 知识库检索
  -> 找到相关文档片段
  -> 把片段交给大模型
  -> 模型基于片段回答
```

最初的实现参考了智谱官方文档中的 `tools.retrieval` 方式：

```json
{
  "model": "glm-4.6",
  "messages": [
    {
      "role": "user",
      "content": "用户问题"
    }
  ],
  "tools": [
    {
      "type": "retrieval",
      "retrieval": {
        "knowledge_id": "YOUR_KNOWLEDGE_BASE_ID",
        "prompt_template": "从文档中寻找答案"
      }
    }
  ]
}
```

请求可以正常返回 HTTP 200，但回答内容和知识库没有关系。

## 二、问题现象

假设知识库中明确存在：

```text
星云网关的默认管理端口是 8080。
```

用户询问：

```text
星云网关的默认管理端口是多少？
```

页面上显示了 `RAG` 标记，但模型回答的却是：

```text
我没有找到足够的信息，请提供更多上下文。
```

或者回答了一些与项目无关的通用知识。

这里有两个非常容易误判的地方：

1. HTTP 200 不代表知识库检索成功；
2. 模型返回了非空文本，也不代表文档片段进入了模型上下文。

## 三、排错：问题不一定在知识库 ID

排查 RAG 问题时，建议先把“检索”和“生成”分开验证。

### 3.1 检查知识库和文档状态

我依次确认了：

- 知识库 ID 存在；
- 文档列表接口能返回文档；
- 文档的向量化状态正常；
- 直接调用知识库检索接口能够命中目标片段。

这一步非常重要。如果直接检索都查不到内容，继续更换聊天模型或修改提示词通常没有意义。

### 3.2 对比检索接口和对话接口

直接调用知识库检索接口时，返回数据类似：

```json
{
  "code": 200,
  "message": "请求成功",
  "data": [
    {
      "text": "星云网关的默认管理端口是 8080。",
      "score": 0.96,
      "metadata": {
        "doc_name": "产品手册.docx",
        "doc_id": "DOCUMENT_ID"
      }
    }
  ]
}
```

但走旧的 `tools.retrieval` 对话接口时，响应中的关键是：

```json
{
  "usage": {
    "prompt_tokens": 10
  }
}
```

`prompt_tokens` 只有 10，基本只能容纳用户问题本身，说明文档片段并没有进入对话上下文。

如果知识库片段真的被注入，提示词 token 通常会明显增加，因为文档文本会一起发送给模型。

### 3.3 检查业务代码如何判断成功

旧代码的逻辑大致是：

```java
JsonNode choices = responseJson.get("choices");
if (choices != null && !choices.isEmpty()) {
    String content = choices.get(0)
            .get("message")
            .get("content")
            .asText();

    if (!content.isEmpty()) {
        response.setFromKnowledgeBase(true);
    }
}
```

这段代码的问题在于：

```text
content 非空 != 知识库召回成功
```

模型即使用自己的常识回答，`content` 也会非空。于是，检索失败的结果被错误标记成了 RAG。

## 四、旧方法为什么不行

### 4.1 `tools.retrieval` 的检索过程不透明

`tools.retrieval` 把检索交给对话模型和工具编排层处理。业务代码拿到的主要是最终回答，无法直接得到：

- 检索是否真的执行；
- 召回了哪些片段；
- 每个片段的相关性分数；
- 文档片段是否进入了模型上下文。

在当前项目使用的模型和账号组合下，对话接口返回了成功结果，但实际没有有效注入知识库内容。

### 4.2 旧提示词允许模型使用自身知识兜底

旧提示词中有一句类似：

```text
如果找不到答案，就用自身知识回答，并告诉用户该信息不是来自文档。
```

这会导致检索失败时仍然生成一段看起来合理的回答，从而掩盖真正的问题。

对 RAG 来说，知识库没有召回时应该明确返回“没有找到相关内容”，而不是让模型自由发挥。

### 4.3 业务层没有校验 `data[]`

旧实现没有检查知识库检索结果，只检查了模型是否返回文本。正确的判断依据应该是：

```text
检索响应 code == 200
且 data[] 中存在非空片段
且片段数量大于 0
```

只有这些条件都满足，才可以把 `fromKnowledgeBase` 设为 `true`。

### 4.4 Python 示例无法直接照搬到 Java

智谱官网的知识库调用示例以 Python SDK 为主，示例形式类似：

```python
response = client.chat.completions.create(
    model="glm-4.6",
    messages=[{"role": "user", "content": "用户问题"}],
    tools=[
        {
            "type": "retrieval",
            "retrieval": {
                "knowledge_id": "YOUR_KNOWLEDGE_BASE_ID",
                "prompt_template": "从文档中寻找答案"
            }
        }
    ]
)
```

但当前项目是 Java/Spring Boot。项目中使用过智谱 Java SDK `oapi-java-sdk:1.0.6`，其中没有找到可以直接调用的知识库检索客户端封装。

这不代表 Java 无法接入。知识库本身提供了标准 HTTP API，Java 完全可以自行封装请求。

## 五、方案思考：为什么要改成两阶段 RAG

我把可选方案整理如下：

| 方案 | 优点 | 问题 | 选择 |
| --- | --- | --- | --- |
| 继续使用 `tools.retrieval`，只改提示词 | 改动最小 | 无法保证检索真正执行，仍可能返回模型常识 | 放弃 |
| 增加 Python 服务 | 可以直接复用官方示例 | 多一套运行时和部署链路 | 不需要 |
| 只依赖现有 Java SDK | 保持 Java 技术栈 | 没有现成的知识库检索封装 | 不满足 |
| Java 直接调用知识库 HTTP API | 结果可验证，不依赖 Python，便于测试 | 需要自己封装 JSON | 采用 |
| 自建向量数据库 | 控制力强 | 文档同步、索引、嵌入模型都要自己维护 | 暂不采用 |

最终方案是：

```text
第一步：Java 显式调用 knowledge/retrieve，先拿到真实文档片段
第二步：把片段放进受约束的系统提示词，再调用模型生成回答
```

这样做之后，检索和生成可以分别验证：

- 检索失败：检查接口返回和文档状态；
- 生成失败：检查模型请求和上下文；
- 没有召回：不调用回答模型，也不显示 RAG；
- 召回成功：可以记录片段数量、文档名和相关性分数。

## 六、Java 调用智谱知识库

### 6.1 配置文件

不要把 API Key 和知识库 ID 写死在代码或公开配置文件中。建议使用环境变量：

```yaml
zhipu:
  api-key: ${ZHIPU_API_KEY}
  knowledge-base-id: ${ZHIPU_KNOWLEDGE_BASE_ID}

  # 知识库检索接口
  retrieval-url: https://open.bigmodel.cn/api/llm-application/open/knowledge/retrieve

  # 对话生成接口
  api-url: https://open.bigmodel.cn/api/paas/v4/chat/completions

  model: glm-4.6
  top-k: 5
  top-n: 20
  recall-method: mixed
```

环境变量示例：

```bash
export ZHIPU_API_KEY="YOUR_ZHIPU_API_KEY"
export ZHIPU_KNOWLEDGE_BASE_ID="YOUR_KNOWLEDGE_BASE_ID"
```

Windows PowerShell：

```powershell
$env:ZHIPU_API_KEY = "YOUR_ZHIPU_API_KEY"
$env:ZHIPU_KNOWLEDGE_BASE_ID = "YOUR_KNOWLEDGE_BASE_ID"
```

如果没有现成的 `RestTemplate` Bean，可以补一个配置类：

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
```

生产环境建议给 `RestTemplate` 配置连接超时和读取超时，避免外部接口异常时请求长期挂起。

### 6.2 定义召回结果

用一个结果对象把“是否召回”和“召回内容”明确表达出来：

```java
import java.util.List;

public record KnowledgeResult(
        boolean found,
        String context,
        List<Chunk> chunks
) {
    public KnowledgeResult {
        context = context == null ? "" : context;
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    public static KnowledgeResult empty() {
        return new KnowledgeResult(false, "", List.of());
    }

    public record Chunk(String documentName, String text, double score) {
    }
}
```

### 6.3 Java 检索与回答服务

下面的代码使用 `RestTemplate + Jackson`，不依赖 Python：

```java
package com.example.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ZhipuKnowledgeService {

    private static final int MAX_CONTEXT_LENGTH = 15_000;

    @Value("${zhipu.api-key}")
    private String apiKey;

    @Value("${zhipu.knowledge-base-id}")
    private String knowledgeBaseId;

    @Value("${zhipu.retrieval-url}")
    private String retrievalUrl;

    @Value("${zhipu.api-url}")
    private String apiUrl;

    @Value("${zhipu.model:glm-4.6}")
    private String model;

    @Value("${zhipu.top-k:5}")
    private int topK;

    @Value("${zhipu.top-n:20}")
    private int topN;

    @Value("${zhipu.recall-method:mixed}")
    private String recallMethod;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ZhipuKnowledgeService(
            RestTemplate restTemplate,
            ObjectMapper objectMapper
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 显式调用知识库检索接口。
     */
    public KnowledgeResult retrieve(String query) {
        if (query == null || query.isBlank()) {
            return KnowledgeResult.empty();
        }

        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("request_id", UUID.randomUUID().toString());
            request.put("query", query.trim());

            ArrayNode knowledgeIds = request.putArray("knowledge_ids");
            knowledgeIds.add(knowledgeBaseId);

            request.put("top_k", clamp(topK, 1, 20));
            request.put("top_n", clamp(topN, 1, 100));
            request.put("recall_method", normalizeRecallMethod());
            request.put("rerank_status", 0);

            JsonNode response = postJson(retrievalUrl, request);

            int code = response.path("code").asInt(200);
            if (code != 200) {
                throw new KnowledgeException(
                        "知识库检索失败: " + response.path("message").asText()
                );
            }

            JsonNode data = response.path("data");
            if (!data.isArray() || data.isEmpty()) {
                return KnowledgeResult.empty();
            }

            List<KnowledgeResult.Chunk> chunks = new ArrayList<>();
            Set<String> seenTexts = new LinkedHashSet<>();

            for (JsonNode item : data) {
                String text = item.path("text").asText("").trim();
                if (text.isEmpty() || !seenTexts.add(text)) {
                    continue;
                }

                String documentName = item.path("metadata")
                        .path("doc_name")
                        .asText("未命名文档");

                chunks.add(new KnowledgeResult.Chunk(
                        documentName,
                        text,
                        item.path("score").asDouble(0)
                ));
            }

            if (chunks.isEmpty()) {
                return KnowledgeResult.empty();
            }

            return new KnowledgeResult(
                    true,
                    buildContext(chunks),
                    chunks
            );
        } catch (KnowledgeException e) {
            throw e;
        } catch (Exception e) {
            throw new KnowledgeException("知识库检索请求失败", e);
        }
    }

    /**
     * 只根据已召回的片段生成回答。
     */
    public String answer(String question, KnowledgeResult result) {
        if (result == null || !result.found()) {
            throw new KnowledgeException("知识库没有召回可用于回答的内容");
        }

        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", model);

            ArrayNode messages = request.putArray("messages");

            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", """
                    你是知识库问答助手。
                    只能依据下面提供的知识库片段回答问题。
                    不得使用片段之外的常识，不得编造。
                    如果片段不足以回答，请明确说明：
                    “知识库中未找到足够信息”。

                    知识库片段：
                    """ + result.context());

            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", question);

            request.put("temperature", 0.1);
            request.put("max_tokens", 1024);
            request.put("stream", false);

            JsonNode response = postJson(apiUrl, request);
            String content = response.path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText("")
                    .trim();

            if (content.isEmpty()) {
                throw new KnowledgeException("模型没有返回有效回答");
            }
            return content;
        } catch (KnowledgeException e) {
            throw e;
        } catch (Exception e) {
            throw new KnowledgeException("知识库回答生成失败", e);
        }
    }

    private JsonNode postJson(String url, ObjectNode request) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> entity = new HttpEntity<>(
                objectMapper.writeValueAsString(request),
                headers
        );

        String response = restTemplate.postForObject(url, entity, String.class);
        if (response == null || response.isBlank()) {
            throw new KnowledgeException("知识库接口返回空响应");
        }
        return objectMapper.readTree(response);
    }

    private String buildContext(List<KnowledgeResult.Chunk> chunks) {
        StringBuilder context = new StringBuilder();
        int index = 1;

        for (KnowledgeResult.Chunk chunk : chunks) {
            String header = "【片段 " + index + "｜文档："
                    + chunk.documentName() + "】\n";

            int remaining = MAX_CONTEXT_LENGTH
                    - context.length()
                    - header.length();
            if (remaining <= 0) {
                break;
            }

            String text = chunk.text();
            if (text.length() > remaining) {
                text = text.substring(0, remaining) + "…";
            }

            context.append(header).append(text).append("\n\n");
            index++;
        }
        return context.toString().trim();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String normalizeRecallMethod() {
        String value = recallMethod == null
                ? "mixed"
                : recallMethod.trim().toLowerCase();

        return switch (value) {
            case "embedding", "keyword", "mixed" -> value;
            default -> "mixed";
        };
    }

    public static class KnowledgeException extends RuntimeException {
        public KnowledgeException(String message) {
            super(message);
        }

        public KnowledgeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
```

这段代码的核心是两个独立动作：

1. `retrieve()` 只负责拿片段；
2. `answer()` 只负责基于片段生成答案。

不要把它们重新合并成一次不可观测的 `tools.retrieval` 调用。

### 6.4 在业务层判断 RAG 是否成功

业务层不应该看“模型是否返回了文字”，而应该看“检索结果是否真的有片段”：

```java
KnowledgeResult result = knowledgeService.retrieve(question);

if (result.found()) {
    response.setFromKnowledgeBase(true);
    response.setKnowledgeBaseContent(result.context());
    response.setReply(knowledgeService.answer(question, result));
} else {
    response.setFromKnowledgeBase(false);
    response.setReply("知识库中未检索到与问题相关的内容。");
}
```

如果还要走 SQL Agent，则把 `result.context()` 作为 SQL 生成上下文，而不是把本地知识库上下文丢掉：

```java
String sql = deepSeekService.generateSQL(
        question,
        dbType,
        tableSchema,
        temperature,
        result.context()
);
```

## 七、两阶段 RAG 的数据流

```text
用户问题
  |
  v
POST /knowledge/retrieve
  |
  +-- code != 200 -----------------> 报错，不标记 RAG
  |
  +-- data[] 为空 -----------------> 返回“未检索到”，不调用回答模型
  |
  +-- data[] 有内容
          |
          v
    按 text 去重
          |
          v
    拼接文档名和片段
          |
          v
    上下文最多 15,000 字符
          |
          v
    调用 glm-4.6
          |
          v
    只依据片段回答
```

### 7.1 `top_n` 和 `top_k` 的区别

| 参数 | 作用 |
| --- | --- |
| `top_n` | 初始召回候选数量，示例配置为 20 |
| `top_k` | 最终返回给业务的片段数量，示例配置为 5 |

可以把它们理解为：

```text
先粗筛 top_n 个候选
  -> 做相关性排序
  -> 最终取 top_k 个片段
```

`sourceCount` 不是知识库文档总数，而是最终响应 `data[]` 去重后的有效片段数。

### 7.2 知识库变大后会不会很慢

不会把所有文档都塞进大模型，也不会让大模型逐个阅读全部文档。

每次请求最终发送给模型的只有：

- 最多 `top_k` 个片段；
- 拼接后最多 15,000 个字符。

文档数量增加会影响知识库解析、向量化和索引管理，但不会线性增加单次问答的 prompt 大小。检索服务会先完成候选筛选。

### 7.3 重排要不要开

检索请求中有一个参数：

```json
{
  "rerank_status": 0
}
```

`0` 表示不额外启用重排，`1` 表示启用。启用后可以配合：

```json
{
  "rerank_status": 1,
  "rerank_model": "rerank"
}
```

适合以下情况：

- 召回了很多相似片段，但真正准确的没有排到前面；
- 姓名、编号、业务术语容易被语义相似的内容干扰；
- 增大 `top_k` 后回答质量反而下降。

重排会增加一次排序成本，建议用真实问题集比较准确率和延迟后再开启。

## 八、由哪个 AI 回答

这是知识库与 SQL Agent 结合时最容易混淆的部分。

| 模式 | 处理方式 |
| --- | --- |
| 只开启知识库 | 智谱召回片段，再由智谱 `glm-4.6` 基于片段回答，不走 DeepSeek |
| 同时开启知识库和 SQL Agent | 智谱负责召回片段，片段作为上下文交给 DeepSeek 生成 SQL |
| 只开启 SQL Agent | 不检索知识库，DeepSeek 直接生成 SQL |
| 两个开关都关闭 | DeepSeek 进行普通对话 |

用一句话概括：

```text
纯 RAG：智谱查资料，智谱回答。
知识库 + SQL Agent：智谱查资料，DeepSeek 写 SQL，程序执行并展示结果。
```

对应的调用关系：

```text
KnowledgeBaseService.retrieve()
  -> 智谱 knowledge/retrieve

KnowledgeBaseService.answer()
  -> 智谱 chat/completions
  -> glm-4.6

SQLAgentService
  -> DeepSeek generateSQL()
  -> 将知识库片段作为 SQL 生成上下文
```

## 九、配置参数说明

### 9.1 智谱相关配置

| 配置 | 含义 |
| --- | --- |
| `zhipu.api-key` | 智谱 API Key，建议通过环境变量注入 |
| `zhipu.knowledge-base-id` | 知识库 ID，建议通过环境变量注入 |
| `zhipu.retrieval-url` | 知识库检索接口，只返回文档片段 |
| `zhipu.api-url` | 对话接口，用于生成 RAG 回答 |
| `zhipu.model` | 回答模型，例如 `glm-4.6` |
| `zhipu.top-k` | 最终片段数量上限 |
| `zhipu.top-n` | 初始候选数量上限 |
| `zhipu.recall-method` | `embedding`、`keyword` 或 `mixed` |

注意：`retrieval-url` 和 `api-url` 是两个不同接口，不能混用。

### 9.2 检索请求参数

| 参数 | 含义 |
| --- | --- |
| `request_id` | 请求唯一标识，便于定位日志 |
| `query` | 用户问题，接口限制为 1000 字以内 |
| `knowledge_ids` | 知识库 ID 数组 |
| `top_k` | 最终返回片段数，范围 1～20 |
| `top_n` | 初始候选数，范围 1～100 |
| `recall_method` | 向量、关键词或混合检索 |
| `rerank_status` | 是否开启额外重排，0 或 1 |
| `rerank_model` | 重排模型，可选 `rerank`、`rerank-pro` |

### 9.3 回答生成参数

| 参数 | 示例值 | 作用 |
| --- | --- | --- |
| `model` | `glm-4.6` | 生成回答的模型 |
| `temperature` | `0.1` | 降低自由发挥，尽量忠实于片段 |
| `max_tokens` | `1024` | 回答最大长度 |
| `stream` | `false` | 当前为非流式返回 |

### 9.4 SQL Agent 配置

如果同时启用 SQL Agent，还需要关注：

| 配置 | 含义 |
| --- | --- |
| `sql-agent.vote-count` | 生成并比较多少个候选 SQL |
| `sql-agent.temperature` | SQL 候选生成的温度 |
| `sql-agent.max-retry-times` | SQL 失败后的最大反馈修正次数 |

在纯 RAG 模式下，这些 SQL 参数不会参与回答生成。

## 十、安全注意事项

### 10.1 不要提交 API Key

错误做法：

```yaml
zhipu:
  api-key: YOUR_REAL_API_KEY
```

正确做法：

```yaml
zhipu:
  api-key: ${ZHIPU_API_KEY}
```

同时确认 `.gitignore` 不会提交真实的环境变量文件。

### 10.2 不要在日志中打印完整内容

日志可以记录：

```text
knowledgeId=...
queryLength=...
sourceCount=...
contextLength=...
answerLength=...
```

不建议记录：

- Authorization Header；
- 完整 API Key；
- 用户完整问题；
- 知识库完整原文；
- 临时文档下载 URL。

### 10.3 不要混淆“接口成功”和“召回成功”

以下判断是不够的：

```java
if (answer != null && !answer.isEmpty()) {
    fromKnowledgeBase = true;
}
```

应该判断：

```java
if (result.found() && !result.chunks().isEmpty()) {
    fromKnowledgeBase = true;
}
```

这是让 RAG 可观测、可排错的关键。

## 十一、常见问题清单

### 问题 1：HTTP 200，但没有知识库内容

检查：

1. 是否调用了正确的 `knowledge/retrieve` 接口；
2. `knowledge_ids` 是否是实际知识库 ID；
3. 文档是否完成解析和向量化；
4. `data[]` 是否为空；
5. 请求是否只返回了模型自身回答。

### 问题 2：直接检索有结果，对话接口没有结果

优先检查 `prompt_tokens` 和请求结构。如果 token 数只覆盖用户问题，说明上下文没有真正注入。

### 问题 3：RAG 标记不正确

`fromKnowledgeBase` 不能由前端开关决定，也不能只看模型返回文本。它应该由后端根据检索响应 `data[]` 推导。

### 问题 4：片段太多导致回答变差

不要盲目提高 `top_k`。先看召回质量和排序质量，再决定是否开启重排，或者适当减少片段数量。

### 问题 5：Java 项目是否需要引入 Python

不需要。只要官方服务提供 HTTP 接口，Java 就可以使用 `RestTemplate`、`WebClient` 或 `HttpClient` 调用。Java SDK 没有封装某个功能，不等于 Java 无法接入。

## 十二、验证结果

使用虚构知识库进行验证：

```text
文档：产品手册.docx
片段：星云网关的默认管理端口是 8080。

用户问题：星云网关的默认管理端口是多少？
召回结果：data[] 包含上述片段，score=0.96
最终回答：星云网关的默认管理端口是 8080。
```

验证重点不是“模型碰巧答对了”，而是以下证据链完整：

```text
检索请求成功
  -> data[] 非空
  -> 片段进入系统上下文
  -> 回答模型只能使用片段
  -> 最终回答包含片段中的事实
```

建议为以下行为补单元测试：

- 检索请求包含正确的 `knowledge_ids`；
- `top_k`、`top_n` 和检索方式正确写入；
- 召回片段被拼进回答请求；
- 空结果不调用回答模型；
- 错误码不会被静默当成“没有结果”；
- 只有真正召回内容时才标记 RAG。

## 十三、总结

这次问题的核心不是“Java 不能调用智谱知识库”，而是：

1. 旧方案依赖 `tools.retrieval` 的隐式工具调用；
2. 当前请求没有真正把知识库片段注入模型；
3. 旧业务逻辑又把非空回答误判成了 RAG 成功。

解决思路可以总结为三句话：

```text
先单独验证检索，再验证生成。
检索结果为空，就不要让模型自由发挥。
只有 data[] 真的非空，才显示 RAG。
```

如果没有官方 Java 示例，直接根据官方 OpenAPI/HTTP 定义封装请求即可。`RestTemplate + Jackson` 已经足够完成知识库检索、片段拼接和受约束回答，不需要额外引入 Python 服务。

对于知识库规模较大的项目，不必担心“每次让模型读完整个知识库”。检索服务会先通过向量/关键词混合检索筛选候选，业务只把少量最相关片段交给模型；真正的优化重点是 `top_k`、召回质量、重排和上下文长度，而不是把全部文档塞进 prompt。
