# 测试说明

## 1. 后端测试

```powershell
cd backend
.\mvnw.cmd clean test
.\mvnw.cmd clean package
```

现有覆盖：

| 测试 | 覆盖 |
|---|---|
| `PolicyRuleEngineTest` | 选科必选/任选、预算、合作办学、控制线、特殊路径、体检等硬规则 |
| `RecommendationEngineTest` | 阈值边界、冲稳保、专业/城市偏好、缺失位次 |
| `RiskAndComplianceEngineTest` | 保底不足、候选为空、过度承诺与免责声明 |
| `ProfileServiceTest` | 自然语言档案抽取、完整性和低信息追问 |
| `AdmissionDataImporterTest` | 新旧类型映射、导入语义、控制线、缺失位次、31 条全量幂等 |
| `WorkflowApiIntegrationTest` | 实际 HTTP 登录、Cookie、会话、演示咨询、运行竞态、报告、证据、SSE 持久回放 |
| `AiConfigurationApiIntegrationTest` | 管理员权限、动态配置、AES-GCM 密文、Key 掩码和 Mock 连通性测试 |
| `AdmissionAdvisorWorkflowRedisQueueTest` | 消费组已存在但建组异常被框架包装时，worker 仍继续读取任务 |
| `InfrastructureContainersTest` | PostgreSQL/pgvector 与 Redis 容器可达性；无 Docker 时跳过 |

端到端测试使用 Mock LLM，确保不消耗外部 Key。Redis 不可用时验证本地回退仍完成报告。

## 2. 前端验证

```powershell
cd frontend
npm ci
npm run lint
npm run build
```

构建执行 ESLint、React Hooks 规则、TypeScript strict、9 个应用页面和同源 API 动态路由生成。人工/浏览器验证清单：

1. 未登录首次进入会自动跳转显式登录页，不需要手动 Reload。
2. 登录页可用演示账号登录，也可主动选择游客体验。
3. 发送完整河南场景，能看到真实 SSE 节点事件。
4. 报告画布显示冲稳保、候选、风险、证据和数据版本。
5. 刷新或切换历史会话后，`REPORT_READY` 消息恢复历史报告。
6. 来源链接展示 URL、质量、发布日期与 cautions。
7. 1100px 以下显示对话/报告切换；720px 以下隐藏侧栏并调整卡片列数。
8. 管理员模型配置页可保存、掩码读取、测试和动态切换 Mock/真实模型。

Playwright 依赖已配置，但仓库当前没有一套在 CI 中自动启动前后端的浏览器脚本；这一点列在限制中，不把手工浏览器检查表述为自动 E2E。

## 3. 已验证结果（2026-07-23）

- `backend .\mvnw.cmd clean test`：通过；共 21 项测试，20 项通过、0 失败，Docker Hub 无法拉取 Ryuk 时 1 项容器测试跳过。
- `backend .\mvnw.cmd clean package`：通过，生成普通 JAR 与 `-exec.jar`。
- 根 Reactor `mvnw clean package`：backend、data-importer、parent 全部 SUCCESS。
- `frontend npm run lint`：通过，0 error / 0 warning。
- `frontend npm run build`：通过，所有页面和 `/api/backend/[...path]` 生成成功。
- 实际 HTTP：health、HttpOnly Cookie、会话、报告和 SSE 回放已验证；福建历史类、今年 550 分、法学、本科批返回 3 个 2025 历史参考候选。
- Docker：PostgreSQL/pgvector、Redis、Spring Boot 和 Next.js 全部实际运行；旧数据卷 Flyway 版本为 `101`，福建补充数据与阈值已核对。
- 浏览器：登录入口、自动加载、桌面三栏和 390px 手机报告均实测，截图位于 `docs/screenshots/`。

## 4. 故障回归

本次测试曾发现并修复：

- `rank-conversion` 未归一导致 6 条位次记录业务键碰撞。
- 任务刚排队时 `GET /runs/{id}` 因可空 state 使用 `Map.of` 抛 NPE。
- Spring Security 无状态过滤器在 SSE async dispatch 跳过鉴权。
- 未登录被错误返回 403，导致游客模式不启动。
- 浏览器直连 8080 导致 Cookie/CORS/客户端拦截，改用 Next 同源代理。
- 历史会话刷新后右侧报告未恢复。
- “今年”和单独输入 `2026` 未被识别，且跨省咨询保留旧位次/分数上下文。
- 福建演示库没有院校记录导致候选为 0，新增 10 条官方历史补充数据与阈值。
- Redis 消费组已存在时，外层异常缺少 `BUSYGROUP` 文本，worker 反复建组但不读取任务。
