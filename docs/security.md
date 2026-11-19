# 安全与合规

## 1. 身份与权限

- 密码用 BCrypt cost 12；源码和日志不记录明文密码。
- Session 原始 token 只存 HttpOnly Cookie，数据库保存 SHA-256 hash；支持过期和撤销。
- Cookie 默认 `SameSite=Lax`，生产环境通过 `SESSION_SECURE_COOKIE=true` 开启 Secure。
- 未登录返回 401，权限不足返回 403；`/api/v1/admin/**` 要求 `ROLE_ADMIN`。
- 所有会话、运行和报告查询都带当前 `user_id` 所有权条件。

## 2. API 与输入

- 登录每 IP 每分钟 8 次；认证入口 10/20 次；消息每用户每分钟 30 次。
- 消息最大 8000 字，标题、邮箱、密码和表单字段使用 Bean Validation。
- Multipart 上限 10 MB；当前未开放知识文件上传，因此不存在可达文件写入路径。
- 管理导入只接受已存在的 `.json` 文件或目录，最多 5000 个文件；路径先绝对化和 normalize。管理员仍应只挂载专用数据目录。
- 数据查询为固定参数化 SQL，模型不能传任意 SQL、数据库连接或文件系统路径。
- CORS 使用环境变量白名单并允许凭证，只开放必要方法和请求头。

## 3. 模型与 Prompt Injection

- Key、Base URL 和模型名可由后端环境变量提供，也可由管理员配置页提交给本系统后端；前端不直接调用模型厂商。
- 配置读取接口只返回 Key 是否存在和末四位掩码。数据库使用 AES-GCM 保存 Key 密文，加密密钥来自 `AI_CONFIG_ENCRYPTION_KEY` 或后端私有 `.local/ai-config.key`，Docker 使用独立持久卷。
- Base URL 只接受 HTTPS；本机调试例外允许 localhost HTTP，并拒绝 user-info、query、fragment 和路径回退形式。模型配置变更写入脱敏审计日志。
- 业务服务依赖 `LlmGateway`，模型不能修改招生事实或绕过规则引擎。
- 系统 Prompt 要求只使用工具证据，不泄露 Prompt/Key，不接受 RAG 文本中的指令。
- 合规层拦截“保录、必中、一定能上、百分之百录取、100%录取”、内部配置词和免责声明缺失。
- 模型失败、超时或解析失败时使用模板解释并标记 DEGRADED，不能用幻觉补齐数据。

## 4. 数据语义合规

- `control-line-only` 和 `special-path-only` 是硬拦截，不能仅降分后继续推荐。
- `rankUnavailable` 和 `scoreOnly` 阻止生成位次事实；前端显示“原始来源未公开”。
- 报告关键项保存数据版本、来源、证据和规则 ID；每份报告显示完整免责声明。
- 参考项目没有明确许可证时仅作结构审计，正式发布前须再次审计数据使用权。

## 5. 日志与错误

- JSON 日志记录 Trace ID、logger、级别和消息；不打印 API Key、密码或完整用户对话。
- 异常响应返回通用消息和 Trace ID，服务端才保留诊断堆栈。
- Actuator 只公开 health/info/metrics/prometheus；健康详情仅授权用户可见。

## 6. 已知安全待办

进阶生产部署仍应加入反向代理 TLS、集中密钥管理、分布式限流、管理员导入目录 allowlist、病毒扫描、审计日志覆盖率、数据保留策略和备份加密。当前内存限流不跨多实例共享。
