# API 设计

统一前缀：`/api/v1`。前端浏览器使用同源 `/api/backend/*`，由 Next.js 服务端逐字转发路径、查询参数、Cookie、幂等键、Trace ID 与 SSE 流。

## 1. 通用约定

- 身份：`WENJIN_SESSION` HttpOnly Cookie。
- JSON：UTF-8；请求最大长度由 DTO 和服务器共同限制。
- 幂等：消息接口接受 `Idempotency-Key`；缺失时由会话、消息和档案版本计算。
- 错误：`status`、`code`、`message`、`path`、`traceId`、`timestamp`，不返回堆栈。
- SSE：事件带递增 `id`；重连传 `Last-Event-ID`。

## 2. 已实现端点

### 认证

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/auth/register` | 注册并设置 Session Cookie |
| POST | `/auth/login` | 登录，IP 限流 |
| POST | `/auth/logout` | 撤销 Session 与清 Cookie |
| GET | `/auth/me` | 当前用户 |
| POST | `/auth/anonymous` | 创建游客用户 |
| DELETE | `/auth/me` | 删除当前账号及关联私有数据 |

### 档案与会话

`GET/PUT/PATCH /profiles/current`、`GET /profiles/current/completeness`。  
`POST/GET /conversations`、`GET/PATCH/DELETE /conversations/{id}`、`GET/POST /conversations/{id}/messages`。

消息成功返回：

```json
{"conversationId":"...","messageId":"...","runId":"...","status":"QUEUED"}
```

### 运行、SSE 与报告

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/runs/{id}` | 运行摘要与可空状态；刚排队时 `state` 可为 null |
| GET | `/runs/{id}/events` | SSE 持久回放与实时订阅 |
| POST | `/runs/{id}/retry` | 用新幂等键重试 |
| POST | `/runs/{id}/cancel` | 设置取消标记 |
| GET | `/reports` | 当前用户报告列表 |
| GET | `/reports/{id}` | 结构化报告 |
| GET | `/reports/{id}/evidence` | 证据和规则结果 |
| POST | `/reports/{id}/refine` | 返回在原会话发送调整条件的指引 |

### 公共数据

`GET /universities/search?q=&limit=`、`GET /majors/search`、`GET /sources/{id}`、`GET /datasets/coverage?province=&year=`。搜索结果为招生事实投影，不是全国院校主数据全集。

### 管理员

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/admin/imports` | 扫描 JSON 文件/目录并执行 dry-run 或 UPSERT |
| GET | `/admin/imports` | 导入批次列表 |
| GET | `/admin/imports/{id}` | 批次与失败明细 |
| GET | `/admin/data-quality` | 记录、来源、缺失位次和特殊路径统计 |
| GET | `/admin/diagnostics` | 运行与报告数量 |
| GET | `/admin/ai-config` | 读取当前模型配置，不返回完整 API Key |
| PUT | `/admin/ai-config` | 保存并动态应用模型配置；Key 使用 AES-GCM 密文保存 |
| POST | `/admin/ai-config/test` | 使用当前配置执行最小连通性测试 |

## 3. SSE 数据

```text
id: 12
event: recommendation_completed
data: {"timestamp":"...","data":{"candidateCount":10,"tierSummary":{...}}}
```

终态为 `report_completed`、`run_failed` 或 `profile_question_required`。服务器发送初始 heartbeat，浏览器断线后用最后事件 ID 回放。

## 4. 部分端点

`POST /volunteer-plans/check` 当前只接受请求并说明报告内已有整表风险检查，不保存独立手工志愿表；`recommendations/*` 独立资源端点未暴露，推荐通过运行与报告资源读取；知识上传 UI/端点尚未实现。它们不会影响演示咨询主链路，状态在 `known-limitations.md` 明确记录。
