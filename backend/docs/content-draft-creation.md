# 从历史修订创建新草稿

[返回后端入口](../README.md) · [内容工作区](content-workspace.md) · [内容审核](content-review.md)

`POST /api/v1/creator/contents/{contentId}/drafts` 对应 `createContentDraft`。
需有效 Bearer、已验证邮箱、ACTIVE 账号及 USER 创作资格，并且内容属于本人。
成功返回 201、`ApiResponse<ContentWorkspace>` 和指向新修订的 Location。
本接口只创建草稿；返回的工作区可以立即用于后续编辑。

```http
POST /api/v1/creator/contents/content_001/drafts
Authorization: Bearer <用户凭证>
Idempotency-Key: <本次操作生成的16～128字符键>
Content-Type: application/json

{"expectedVersion":7,"sourceRevisionId":"revision_002"}
```

## 请求与复制规则

- expectedVersion 必填，取最近工作区的内容聚合版本，范围 1～2147483647；不使用源修订编号代替。
- sourceRevisionId 可选，必须属于当前内容；省略时选择 revisionNumber 最大的修订。显式 null、空串及格式错误返回 422。
- 请求体不接受标题、正文等编辑字段；未知字段、错误 JSON 类型或查询参数返回 400。创建后用 PATCH draft 编辑新草稿。
- Idempotency-Key 必填，16～128 个 Unicode 码点，重复请求头拒绝。内容、源修订 ID 为 1～64 位字母、数字、下划线或短横线。

APPROVED、REJECTED、CANCELLED 源快照原样保留，新建不同的 revisionId，状态固定 DRAFT。
新编号为该内容历史最大 revisionNumber + 1，即使来源是更旧的修订，也不复用旧编号。
复制标题、Markdown 正文和媒体、标签引用；原文不 trim，引用的 usage_type 与 position 原样复制。
只复制素材 ID 和数据库关联，不复制文件，不请求 MinIO，也不推进素材处理状态。

复用已有草稿结构、归属和用途校验；允许保留 WAITING_UPLOAD、PROCESSING、FAILED、READY 素材以及已存在的停用标签。
READY 与 ACTIVE 的发布门槛仍由后续提交审核处理，ARTICLE 不能关联视频，正文图片集合必须与 imageAssetIds 一致。

同一内容已有 DRAFT 或 IN_REVIEW 时返回 409 DRAFT_ALREADY_EXISTS。
除了检查工作指针，也检查数据库生成的 working_content_id；工作指针缺失但实际存在工作修订时仍拒绝创建。
数据库的 uk_revision_working 唯一约束继续兜底，uk_revision_number 保证编号不重复。

## 内容状态及错误

仅 ACTIVE 内容可以创建；DELETED 返回 CONTENT_NOT_EDITABLE，应先恢复。
PUBLISHED、WITHDRAWN、TAKEN_DOWN 保持原状态，发布指针、首次及当前发布时间不变。
TAKEN_DOWN 可创建整改草稿，但不会因此解除下架；新稿仍需提交审核。
新草稿不创建审核记录或 Outbox，不更改源修订、既有审核、公开可见性或媒体状态。

| 场景 | HTTP / code |
| --- | --- |
| 无效身份或认证版本过时 | 401 AUTHENTICATION_REQUIRED |
| 账号受限、无创作资格 | 403 ACCOUNT_RESTRICTED / PERMISSION_DENIED |
| 内容不存在、非本人、源修订不属于当前内容 | 404 RESOURCE_NOT_FOUND |
| 内容版本过时或版本/编号达到上限 | 409 CONTENT_VERSION_CONFLICT，带授权后的 currentVersion |
| 已有工作修订 | 409 DRAFT_ALREADY_EXISTS |
| 已删除内容 | 409 CONTENT_NOT_EDITABLE |
| 同键不同请求 | 409 IDEMPOTENCY_KEY_REUSED |
| 相同请求处理未结束、租约或完成条件失效 | 409 REQUEST_IN_PROGRESS，带正整数 Retry-After |
| 参数违反字段约束 | 422 VALIDATION_ERROR |
| 配额用尽 | 429 RATE_LIMITED，带正整数 Retry-After |
| 数据库、Redis、源快照完整性或成功快照异常 | 503 DEPENDENCY_UNAVAILABLE |

## 幂等与原子事务

幂等作用域为调用者 + createContentDraft + 键，独立于 createContent；有效期 24 小时，处理租约 60 秒。
请求指纹绑定内容路径 ID、expectedVersion 和原始可选 sourceRevisionId，并规范化 JSON 字段顺序。
省略来源保持“选择最新修订”的原始请求含义，不在查询成功重放前重新解析最新来源。

相同键与指纹先重放第一次成功的工作区快照、资源 ID、201 与 Location，再考虑新执行的版本和工作稿条件。
因此草稿已创建或后来编辑，不会让同一次成功请求的网络重试重复创建、覆盖草稿或错误报版本冲突。
重放反映首次业务结果，随后需要当前编辑状态时应重新读取工作区；账号权限和当前归属仍需检查。

有效处理租约返回 REQUEST_IN_PROGRESS；过期租约由新 token 接管。过期 24 小时的键可作为新意图重新使用，旧业务数据保留。
失败释放只允许删除本次 token 仍持有的 PROCESSING 记录，不能删掉新的租约或已经成功的结果。

调用链：

1. [ContentDraftCreationController#create](../src/main/java/com/freecontent/community/content/workspace/controller/ContentDraftCreationController.java)：校验请求体、查询参数与请求头，返回新修订 Location。
2. [ContentDraftCreationServiceImpl#create](../src/main/java/com/freecontent/community/content/workspace/service/impl/ContentDraftCreationServiceImpl.java)：检查身份、路径和幂等键，生成指纹，领取租约；成功重放直接返回，新执行才取得配额并进入写事务。
3. [ContentDraftCreationTransactionService#claim](../src/main/java/com/freecontent/community/content/workspace/service/transaction/ContentDraftCreationTransactionService.java)：短事务重检资格、锁定账号及角色和幂等记录，识别重放、处理中或可领取的请求。
4. ContentDraftCreationTransactionService#finish：重新授权，验证仍持有有效租约，再锁定本人内容和源修订，检查版本、生命周期及工作修订。
5. [ContentWorkspaceReader#frozenRevision](../src/main/java/com/freecontent/community/content/service/read/ContentWorkspaceReader.java) 与现有草稿校验器：读取冻结内容及有序引用，复核数据、归属和用途。
6. [ContentDraftCreationMapper](../src/main/java/com/freecontent/community/content/workspace/mapper/ContentDraftCreationMapper.java)：新建 DRAFT，用 INSERT SELECT 复制关联并核对行数，以条件更新递增内容版本、设置工作指针。
7. 同一个 finish 事务读取新工作区，将成功快照及新修订 ID 写入幂等记录后一起提交。

claim 和 finish 使用 READ_COMMITTED；账号、角色、幂等记录、内容、源修订和素材按既有顺序加锁。
Redis 计数在两次事务之间，finish 必须重新检查资格、租约和内容版本。
任何写入、引用复制、指针更新或成功记录完成失败，新的修订、引用、版本和成功状态整体回滚。
来源选择、复制和结果提交不调用工作区 Controller、公开详情或审核用例。

## 配额与迁移

独立 app.content.draft-creation / `{content-draft-create}`：来源 60 次/分钟，用户 100 次/小时。
成功重放不扣配额，即使当前配额耗尽仍返回原业务结果。新执行失败后再执行会重新计数。
领取租约前拒绝的非法输入、授权失败、处理中请求和指纹冲突不进入配额计数。
创建内容、保存草稿及提交审核的原有配额规则不变。

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| ip-per-minute-limit | CONTENT_DRAFT_CREATION_IP_PER_MINUTE_LIMIT | 60 |
| user-hourly-limit | CONTENT_DRAFT_CREATION_USER_HOURLY_LIMIT | 100 |
| redis-key-prefix | CONTENT_DRAFT_CREATION_REDIS_KEY_PREFIX | fc:content: |

配额必须为正整数，Redis 前缀不能为空或包含花括号；非法配置阻止启动。
V18__content_draft_creation.sql 新增独立幂等表 content_draft_creation_request，保存租约、请求指纹和成功工作区快照。
本机 V18 已执行，V1～V18 均不可改写或删除；本次没有新增依赖、审核事件、后台任务或媒体处理流程。
固定错误契约新增第 38 个操作，原有 37 个操作与原始 openapi.json 保留。
