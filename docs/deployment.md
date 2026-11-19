# 部署说明

## 1. Windows 一键部署

项目根目录提供 `deploy.cmd` 和 `stop.cmd`，适合首次部署或交付给不熟悉 Docker 命令的使用者：

```powershell
cd wenjin-ai-admission
.\deploy.cmd
```

部署脚本会自动完成以下动作：

- 检查 Docker CLI，并在 Docker Desktop 已安装但未运行时尝试启动它；
- 从 `.env.example` 创建 `.env`，生成随机 PostgreSQL 密码和 `AI_CONFIG_ENCRYPTION_KEY`；
- 探测 Docker Hub，网络不可达时自动切换 DaoCloud 镜像地址；
- 构建并启动 PostgreSQL/pgvector、Redis、Spring Boot 后端和 Next.js 前端；
- 使用不经过系统代理的本机 HTTP 探测等待前后端健康，避免 `localhost` 被代理连接拖到超时；
- 等待 `/actuator/health` 与前端首页可访问后输出访问地址和演示账号。

常用维护命令：

```powershell
.\deploy.cmd check
.\deploy.cmd status
.\deploy.cmd logs
.\deploy.cmd up -NoBuild
.\deploy.cmd restart
.\stop.cmd
.\deploy.cmd up -ResetData       # 删除数据卷并重新初始化，谨慎使用
```

默认端口是前端 `3000`、后端 `8080`，可在 `.env` 中修改 `FRONTEND_PORT` 和 `BACKEND_PORT`；如果端口被其他程序占用，脚本会自动选择后续可用端口并同步 CORS。 如需强制 Docker Hub，可使用 `.\deploy.cmd up -UseOfficialRegistry`。

## 2. 本地零依赖模式

后端默认使用 `jdbc:h2:file:./.local/wenjin`、Mock LLM 和进程内 worker。启动：

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

```powershell
cd frontend
npm ci
npm run dev
```

Next 同源代理默认把 `/api/backend` 转发到 `http://localhost:8080/api/v1`。

## 3. Docker Compose

```powershell
docker compose up --build
```

服务：

| 服务 | 端口 | 健康/持久化 |
|---|---:|---|
| frontend | 3000 | 等待 backend healthy |
| backend | 8080 | Dockerfile curl `/actuator/health` |
| postgres | 内部 5432 | `pg_isready`、`postgres-data` |
| redis | 内部 6379 | `redis-cli ping`、AOF、`redis-data` |

Docker profile 启用 PostgreSQL + pgvector 迁移、Redis health 和 Redis consumer group worker。前端用 `BACKEND_INTERNAL_URL=http://backend:8080`，浏览器不需要解析容器主机名。

## 4. 真实模型

推荐方式是先以默认 Mock 模式启动，然后使用管理员账号访问 `/admin/model-settings`，填写 OpenAI-compatible Base URL、API Key 和模型名称，保存后立即生效，并可在同页执行连接测试。

也可以在启动前使用环境变量作为默认配置：

```powershell
Copy-Item .env.example .env
```

编辑 `.env`：

```env
AI_MODE=openai-compatible
AI_BASE_URL=https://api.openai.com/v1
AI_API_KEY=...
AI_CHAT_MODEL=...
AI_FAST_MODEL=...
AI_EMBEDDING_MODEL=...
```

不要把 `.env` 加入版本控制。网页保存的 Key 在数据库中为 AES-GCM 密文，加密密钥位于 `ai-config-data` 卷；生产推荐显式设置 `AI_CONFIG_ENCRYPTION_KEY`，并使用 Docker Secret、Kubernetes Secret 或云密钥管理服务注入。

## 5. 生产建议

- 在 frontend 前部署 HTTPS 反向代理；只公开 3000 或统一域名，不直接公开 PostgreSQL/Redis。
- 将 `SESSION_SECURE_COOKIE=true`，把 `CORS_ALLOWED_ORIGINS` 限制为正式域名。
- 修改默认数据库密码和两个演示账号，或关闭演示账号 Seeder。
- 挂载独立数据导入目录，限制管理员主机文件访问。
- 对 PostgreSQL 做加密备份；公共招生数据与用户隐私数据使用不同备份/保留策略。
- 多实例时让所有 backend 使用同一 Redis/PostgreSQL；consumer name 自动唯一，group 共享。

## 6. 验证

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-WebRequest http://localhost:8080/swagger-ui.html
Invoke-WebRequest http://localhost:3000
```

随后用 README 的演示账号和消息完成一次报告。若 Docker CLI 不可用，可运行本地 H2 端到端测试验证应用代码，但不能声称容器已实际启动。
