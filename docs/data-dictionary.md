# 数据字典

## 1. 核心公共事实

### `admission_records`

| 字段 | 语义 |
|---|---|
| `business_key` | 省份、年份、科类、批次、学校、专业、类型和子类型计算的稳定去重键 |
| `dataset_version` | 本条事实所属数据版本 |
| `province/admission_year/subject_type/batch` | 招生适用范围 |
| `school_name/school_code/city/school_tags_json` | 院校标识与属性 |
| `data_type` | CONTROL_LINE、ADMISSION_PLAN、INSTITUTION_ADMISSION、MAJOR_ADMISSION、SPECIAL_PATH 等 |
| `major_name/major_code/major_group` | 专业或专业组 |
| `elective_requirement/required_subjects_json/any_subjects_json` | 选科原文与结构化要求 |
| `min_score/avg_score/max_score` | 来源公开的分数边界 |
| `min_rank_start/min_rank_end` | 来源公开或明确标注换算的位次区间 |
| `plan_count/tuition` | 招生计划与年度学费；空值表示未公开 |
| `formal_score_scope` | `control-line-only`、`special-path-only`、`school-official-only` 等使用边界 |
| `rank_unavailable` | 原始来源未公开位次，必须保持位次为空 |
| `score_only` | 仅可按分数边界参考，不得输出位次事实 |
| `cautions_json` | 必须展示或纳入风险的来源限制 |
| `source_id/source_url/source_quality/source_published_at` | 来源追溯字段 |
| `raw_json/checksum` | 审计原文与变更检测 |

### `rank_segments`

按省份、年份、科类和分数保存 `rank_start`、`rank_end`、同分人数与来源。导入器把旧格式 `rank-conversion` 归一为 `RANK_SEGMENT`，不会写入普通招生表。

### `data_sources` / `dataset_versions`

来源保存发布者、URL、质量、发布日期、抓取时间、checksum 和 cautions。数据版本保存版本名、状态、覆盖范围、清单 checksum 与激活时间；一次运行在 Data Resolver 后不再切换版本。

## 2. 用户与档案

| 表 | 关键字段与用途 |
|---|---|
| `users` | email、password_hash、display_name、role、anonymous、状态和软删除 |
| `user_sessions` | token_hash、过期时间、撤销时间、最近使用时间；数据库不保存原始 Cookie token |
| `student_profiles` | 省份、年份、分数、位次、科类、批次、预算、城市/专业、接受条件、体检、风险偏好、`field_meta_json`、版本 |
| `student_preferences` | 可扩展的偏好项、权重与来源 |

`field_meta_json` 中每个字段包含 `value`、`confidence`、`sourceMessageId` 和 `updatedAt`。位次另有 `rank_source` 区分官方输入与换算提示。

## 3. 会话、运行与事件

| 表 | 用途 |
|---|---|
| `conversations` | 用户会话标题、状态和软删除 |
| `conversation_messages` | user/assistant、消息类型、文本、结构化 JSON |
| `advisor_runs` | 幂等键、状态、当前节点、数据版本、档案快照、状态 JSON、重试/取消与时间戳 |
| `advisor_run_checkpoints` | 每节点完整状态、递增序号 |
| `advisor_run_events` | SSE `event_id`、事件名、事件 data JSON 和时间 |

运行状态为 QUEUED、RUNNING、WAITING_FOR_PROFILE、COMPLETED、DEGRADED、FAILED 或 CANCELLED。

## 4. 推荐与报告

`recommendation_results` 保存运行、档案、数据版本和策略；`recommendation_items` 保存候选事实引用、层级、总分、分项、理由、证据/规则/来源 ID；`risk_items` 保存风险代码、等级、说明和 blocking 标记。

`reports` 保存完整结构化 JSON、版本、合规结果与软删除；`report_sections` 预留局部重写；`report_evidence` 将结论与 source/rule/dataset 绑定。

## 5. 导入与知识

`import_batches` 保存扫描/成功/失败文件和读、新增、更新、跳过、重复统计；`import_failures` 保存文件、记录索引、原因和截断原文。`knowledge_documents/chunks` 保存文本证据及过滤元数据；PostgreSQL profile 额外提供 `embedding vector(1536)`。

## 6. 模型运行配置

`ai_runtime_config` 使用固定业务键 `active` 保存 mode、provider、Base URL、模型名、超时、重试和 Token 上限。`api_key_ciphertext` 仅保存 AES-GCM 密文，`api_key_hint` 只保存末四位用于页面掩码；`updated_by` 与 `updated_at` 支持配置审计。完整 Key 不进入接口响应或审计详情。
