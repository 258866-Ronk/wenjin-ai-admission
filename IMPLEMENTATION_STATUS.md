# Implementation Status

更新时间：2026-07-23

## 已完成

- 安全解压并审计四个参考 ZIP；未运行、反编译或复用其中的可执行二进制，原 ZIP 未修改。
- Java 21 / Spring Boot 4.1.0 / Spring AI 2.0.0 后端、Next.js 16 / React 19 前端、独立 data-importer 和 Docker Compose。
- 29 张核心业务表加 1 张模型运行配置表、Flyway、H2 本地模式、PostgreSQL/pgvector/Redis Docker 模式。
- 31 条主演示事实和 10 条福建官方历史补充记录；补充包每次启动幂等导入，旧数据卷无需清空即可升级。
- 兼容新旧格式的 Java JSON 导入器、dry-run、过滤、UPSERT、业务键去重、导入批次和失败明细。
- 对话建档、完整性、规则、候选、阈值分层、偏好评分、风险、结构化报告、证据和合规。
- Mock 与 OpenAI-compatible 动态 `LlmGateway`；管理员前端可保存并测试 API，Key 使用 AES-GCM 密文、掩码回显，环境变量仍可作为启动默认值；模型失败模板降级。
- HttpOnly Cookie、BCrypt、游客、管理员、限流、CORS、Trace ID、Swagger、Actuator。
- Redis Streams consumer group worker、ACK、本地回退、幂等任务、取消/重试、checkpoint 和启动恢复。
- SSE 持久事件、Last-Event-ID 回放、异步派发鉴权和 Redis 事件镜像。
- 三栏工作台、移动端 Tab、报告恢复、来源/档案/报告/导入/质量页面、Next 同源 API/SSE 代理。
- README 与要求的 12 份项目文档。
- Windows 一键部署入口 `deploy.cmd`、停止入口 `stop.cmd` 和 PowerShell 核心脚本；自动生成密钥、检测 Docker Desktop、镜像网络切换、健康检查和失败日志汇总。

- 显式登录、注册和游客体验入口；未登录首页转到登录页，后端启动期自动重试，普通用户隐藏管理员工具。
- 新年份缺少院校录取记录时，按省份、科类和兼容批次回退到最近历史年份；年份规则允许明确标注的历史参考、阻断未来年份，省级冲稳保阈值同步回退。
- 修复消息列表滚动 Effect 返回值导致的 React 清理崩溃；咨询提交支持幂等重试，SSE 断流后自动轮询任务状态。
- 一键部署健康检查明确绕过系统代理，避免容器已经健康但 `localhost` 探测仍超时。
- 一键部署的构建启动阶段支持最多 3 次自动重试，覆盖镜像代理 TLS 握手抖动。
- 支持“今年”“本届”和独立四位年份；切换省份、年份、科类时清除旧考试位次/分数上下文。
- Redis 消费组初始化改为查询真实组状态，不再依赖被 Spring Data 包装后的 `BUSYGROUP` 异常文本；卡在 `QUEUED` 的任务可在启动恢复后继续执行。
- 手机端顶部导航改为固定尺寸图标按钮，390px 宽度下不再出现文字竖排。

## 验证通过

```powershell
cd backend
.\mvnw.cmd clean test
.\mvnw.cmd clean package

cd ..
.\mvnw.cmd clean package

cd frontend
npm ci
npm run lint
npm run build
```

- 后端单元、导入和真实 HTTP/SSE 场景通过；Docker 不可用时 1 项 Testcontainers 测试跳过。
- 后端共 21 项测试：20 项通过、0 失败、1 项因 Docker Hub 无法拉取 Testcontainers Ryuk 而跳过。
- 根 Reactor 的 backend、data-importer 和 parent 全部构建成功。
- 前端 lint 0 告警，生产构建成功。
- health、Swagger、HttpOnly Cookie、演示咨询、报告、证据和 SSE 回放实际验证成功。
- Next 同源代理实测完成登录、建会话、生成报告和 SSE 历史回放；返回 `text/event-stream`，按事件 ID 顺序收到 14 个事件，最终为 `report_completed`。
- 管理员模型配置经 Next 同源代理实测：保存/读取成功、Key 原文不回传、缺 Key 的真实模式被拒绝、Mock 连通性测试成功，并可恢复为空 Key 的 Mock 状态。
- Docker Compose 一键部署已在本机实测通过；Docker Hub 直连超时时脚本自动切换 DaoCloud 镜像地址，PostgreSQL/pgvector、Redis、后端和前端均正常启动，健康检查、Swagger、首页、模型配置页和演示账号登录均返回成功。检测到 `8080` 被占用时自动使用 `8081`。
- 浏览器真实复测广东 2026 物理类 680 分/位次 680：检索 4 条 2025 历史记录，硬规则排除 1 项，生成 3 个保底候选；深圳大学、江西理工大学等院校正常展示，报告明确标注 2025 参考年份，前端运行时错误日志为空。
- 运行中的 PostgreSQL 旧数据卷已升级到 Flyway `V101`；福建 2025 历史类 3 条、物理类 7 条，两个科类各 5 条阈值。
- 真实 HTTP 与浏览器复测福建历史类、今年 550 分、法学、本科批：任务正常消费并返回 3 个候选，包含江西理工大学法学和深圳大学法学；桌面/手机截图均通过布局检查。

## 未完成或部分完成

- 完整自动向量化 RAG、知识上传、独立推荐资源 API、手工志愿表持久体检和自动浏览器 E2E CI 尚未完成。
- Refine 会创建新运行并复用持久数据，但尚未实现只重跑单个受影响节点的依赖缓存。
- 演示数据不是全国数据；复杂省级和特殊招生规则需要继续扩充。

详细边界见 `docs/known-limitations.md`。
