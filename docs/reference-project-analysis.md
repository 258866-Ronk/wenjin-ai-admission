# 参考项目审计报告

审计日期：2026-07-19

## 审计方法与边界

四个原始 ZIP 保持不变。解压前逐条检查归档路径，拒绝绝对路径和 `..` 路径穿越；审计期间只读取文本源码、配置和 JSON，不执行参考项目中的脚本、安装器或二进制文件。四个归档均未包含 LICENSE、LICENCE 或 COPYING 文件，因此本项目只借鉴业务边界、数据语义和交互思路，核心代码全部以 Java/TypeScript 重新实现。

| 参考项目 | 文件规模 | 技术栈/形态 | 结论 |
| --- | ---: | --- | --- |
| `jin-guide` | 184 个文件 | FastAPI、SQLAlchemy、LangGraph、Redis/ARQ、PostgreSQL/pgvector、Next.js 14、React 18 | 可借鉴工作流、证据链、SSE 和报告画布；不得复制 Python 实现 |
| `gaokao-zhiyuan-site` | 619 个文件 | 静态 HTML/CSS/JavaScript、Node.js 数据构建脚本、按省 gzip 运行时分片 | 作为最新数据语义和筛选规则参考，版本标识延伸到 v3.311 |
| `gaokao-zhiyuan` | 285 个文件 | 同源静态站点和 Node.js 数据脚本，运行时版本 v3.275 | 作为旧格式和兼容性基线，不与新版重复导入 |
| `zhangxuefeng-skill` | 2 个文件 | README 和图片，无可审计业务源码 | 仅参考“面向家长、通俗解释、回官方核验”的定位；不使用其人物、品牌或外部 Windows 下载物 |

压缩包清单中未发现 `.exe`、`.dll`、`.bat`、`.cmd`、`.msi`。但 `zhangxuefeng-skill` 的 README 引导下载并“Run anyway”执行外部程序，该外部程序不在归档内、无法审计，项目不会下载、运行、反编译或引用它。

## 可复用的业务思路

### `jin-guide`

- 对话建档与低置信字段追问，推荐前检查省份、年份、分数/位次、科类和批次。
- 显式节点状态：档案、数据解析、结构化检索、规则、推荐、风险、报告、合规反思。
- 结构化数据负责事实和硬规则，RAG 只补充解释，LLM 不直接产生分数线或候选学校。
- 数据版本在一次 run 内锁定；推荐项绑定证据、规则结果和来源。
- Redis Stream 记录任务和 SSE 事件，节点完成后保存 checkpoint，前端展示真实进度。
- 三栏工作台：历史会话、对话区、实时报告画布。

### 两个志愿数据项目

- 先按省份、年份、科类、批次和特殊路径做硬过滤，再按位次/分数适配、专业、城市、预算和质量排序。
- 位次优先；只有分数时明确 `scoreOnly`，来源未提供位次时保持 `rankUnavailable=true`。
- `control-line-only` 只能判断批次资格，`special-path-only` 不能进入普通批推荐。
- 冲稳保是区间标签而非伪精确概率。参考实现使用位次差和分差分段，本项目将阈值存入 `province_thresholds`，不散落在前端代码中。
- `cautions`、`sourceUrl`、`sourceQuality`、`sourcePublishedAt` 和原始字段必须贯穿导入、规则、报告和来源页。

## 数据差异

对 `data/admissions` 做了只读解析。旧版 13 个 JSON 中 6 个包含记录，共读取 4,854 条；新版 121 个 JSON 中 36 个包含记录，共读取 25,772 条。两边均无 JSON 解析失败。旧版记录以 `major-admission`、`admission-plan`、`institution-admission` 和少量 `control-line` 为主；新版增加到 22,099 条 `major-admission`、826 条 `control-line`，并保留 2,187 条 `admission-plan`。

新版新增的重要字段包括 `rankEvidenceScope`、`rankDerivedFromScore`、`nativeAdmissionRankUnavailable`、`rankUsage`、`rankUsageLabel`、`rankMetric`、`controlLineKind`、`controlLineRouteKind`、`educationLevel`、`admittedCount`、`filingCount`、`tieBreakScores` 和 OCR 审计字段。导入器采用别名兼容，并把未知字段保留到 `raw_json`，避免新版字段丢失。

抽样确认新版包含河南、广东、江西三省的学校官网分专业记录。河南 2025 计算机相关样本覆盖深圳、武汉、杭州、南昌、赣州、济南等城市；其中杭州电子科技大学原始来源没有最低位次，必须展示为 score-only。广东 2026 官方控制线样本明确标记 `formalScoreScope=control-line-only`。北京 2025 一分一段数据位于 `rankConversions` 根节点，导入器需兼容该数组名。

两个目录间不能简单按文件名累加。新系统以省份、年份、科类、批次、学校、专业/专业组、数据类型和招生子类构造稳定业务键，再结合原始记录 checksum 执行 UPSERT；同键记录按来源质量、发布时间和新版优先级择优。

## 不可复用内容与风险

- 四个项目均无明确许可证，不能复制大段源码、UI 文案或图片，也不能宣称参考数据为本项目原创。
- `zhangxuefeng-skill` 名称、人物联想、下载链接、图片及外部可执行程序全部排除。
- 参考数据混合省级考试院、高校官网、学信网镜像和第三方汇总，商业发布前必须逐条完成来源与许可审计。
- 部分招生数据是控制线、征集计划、特殊路径、score-only 或 OCR 结果，不能升级成普通批专业录取事实。
- `jin-guide` 配置只引用环境变量，未发现硬编码真实 Key；新项目仍不复制任何账号、Cookie、电话、本地路径或配置值。

## 新项目实现方案

- Java 21、Spring Boot 4.1.0、Spring AI 2.0.0，后端只通过 `LlmGateway` 使用模型；默认 Mock 模式可离线演示。
- PostgreSQL/pgvector 保存公共招生事实和知识片段，Redis Streams 保存任务与事件；本地无基础设施时使用 H2 和受控进程内队列降级，确定性规则仍可运行。
- Flyway 建表；独立 Java 数据导入器支持数组、`records`、`rankConversions`、新旧字段、过滤、dry-run、UPSERT、失败记录与统计。
- Java 规则链先硬过滤，再评分和分层；报告 JSON 绑定 `evidenceIds`、`ruleResultIds`、`sourceIds` 和 `datasetVersion`。
- Next.js 三栏工作台消费真实 SSE，移动端切换对话/报告 Tab，不伪造 Agent 行为。

## 发布前检查

1. 为每个演示数据源保存来源 URL、发布者、发布时间、质量、checksum 和用途边界。
2. 补齐第三方项目及数据集许可证或书面授权。
3. 复核最新省级考试院政策、选科要求和招生章程。
4. 对外材料不得使用第三方人物姓名、肖像、声音或“官方产品”等误导表述。

