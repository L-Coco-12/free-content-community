# 作者将内容移入回收站

[返回后端入口](../README.md) · [工作区](content-workspace.md) · [撤回公开](content-publication-withdrawal.md) · [重新公开](content-republication.md)

`DELETE /api/v1/creator/contents/{contentId}?expectedVersion=7` 对应 `deleteMyContent`。
需要有效 Bearer、邮箱已验证、ACTIVE 账号、USER 创作资格及本人资源；管理角色不扩大本人范围。
成功 204 且无响应体，不要求 Idempotency-Key，不接受请求体。

## 参数和重复请求

contentId 为 1～64 位字母、数字、下划线或短横线，非法返回 422。
expectedVersion 为必填且只能出现一次的查询参数，范围 1～2147483647。
缺失、重复、未知查询参数、不可解析整数或非空请求体返回 400；可解析但越界（含超大整数）返回 422。
非法参数不进入限流和数据库事务。

事务内先重新核对账号、authVersion、邮箱和角色，并锁定本人内容。
内容不存在或不属于本人统一返回 404，不泄露 currentVersion。
已经 DELETED 时无副作用返回 204，忽略旧 expectedVersion，不改版本、删除时间、修订、审计或事件。
请求参数仍须合法，身份、归属及限流仍须通过；已删除且版本达到上限也可以重复成功。

实际从 ACTIVE 删除才校验 expectedVersion，版本不匹配或达到上限返回 409 CONTENT_VERSION_CONFLICT，并附 currentVersion。
客户端应重新读取工作区后再决定是否删除。

## 实际删除的变化

| 字段或关联 | 处理 |
| --- | --- |
| lifecycle | ACTIVE → DELETED |
| publicationStatus | 原值保留，包括 UNPUBLISHED、PUBLISHED、WITHDRAWN 和 TAKEN_DOWN |
| version | 增加一次 |
| deletedAt / updatedAt | 更新为本次 UTC 时间 |
| publishedRevisionId / 首次和最近发布时间 | 保留历史指针及时间 |
| 普通 DRAFT | 原样保留，工作指针保留 |
| PENDING 审核 | INVALIDATED，审核版本增加一次，decidedAt 更新，reviewerId/reason 清空 |
| 该审核的 IN_REVIEW 修订 | CANCELLED，仅修改状态及更新时间，正文、标题、引用、创建时间保留 |
| 待审工作链 | 清空 workingRevisionId 和 activeReviewId |

生命周期与发布状态独立，因此 DELETED + PUBLISHED 仍然不可公开。
公开详情、全站/作者列表、作者公开数量和新的公开媒体取址均要求 ACTIVE + PUBLISHED，删除后立即拒绝或过滤。
本人工作区仍可读取，默认创作列表不含该内容，`GET /api/v1/creator/contents?lifecycle=DELETED` 可在回收站查询。

删除不会物理删除历史修订、素材文件、引用、标签或既有审计，也不要求素材已经 READY。
已签发媒体地址仍受原到期时间约束；业务接口停止公开不等于回收已经下载的文件。
TAKEN_DOWN 可移入回收站，但运营下架标记原样保留。

恢复使用[恢复作品](content-restoration.md)：恢复后有历史发布稿为 WITHDRAWN，否则 UNPUBLISHED，原 TAKEN_DOWN 仍保留，旧审核不会复活。
不能直接通过重新公开接口把 DELETED 内容恢复为 ACTIVE。

## 调用链、行锁和回滚

1. `ContentDeletionController#delete`：检查查询白名单、单值参数和空请求体，调用业务接口并返回 204。
2. `ContentDeletionServiceImpl#delete`：身份和参数校验、独立配额、事务调用，数据库及事务异常收口为 503。
3. `ContentDeletionTransactionService#delete`：READ_COMMITTED 事务中重新授权，按账号及角色 → 内容 → 待审记录 → 工作修订顺序加锁。
4. `ContentDeletionMapper`：以归属、ACTIVE、版本条件更新内容，终结待审链，保存最小审计和待发送事件。

待审记录按 pending_content_id、工作修订按 working_content_id 查询，再与聚合指针和 revisionId 交叉核对。
关联缺失、不一致、没有审核却残留 IN_REVIEW，或审核版本已无法递增时返回 503，整体回滚。
本接口契约没有 CONTENT_NOT_EDITABLE、REMEDIATION_REQUIRED 或 MEDIA_NOT_READY，不透传其他用例的专用错误。

内容、审核、修订、审计和所有待发送事件在同一事务提交。任一步更新或插入失败，全部回滚。
与审批、建草稿、重新公开竞争同一聚合版本时只允许一方实际变更，另一方根据当前状态或版本拒绝。
并发重复删除可以都返回 204，只发生一次实际变更和一套审计事件。
未来恢复后再收到旧 DELETE 时，ACTIVE 状态会重新校验版本，不以过去曾删除为成功依据。

## 审计、事件及迁移

V22 新增：

- `content_deletion_audit`：记录作者、内容、原发布状态、可空历史发布修订、失效审核及修订、内容和审核前后版本、requestId 与时间；按内容和删除后版本唯一。
- `content_lifecycle_outbox`：记录 PENDING 的 CONTENT_DELETED，按内容、聚合版本和事件类型唯一；历史发布指针允许为 NULL，支持从未发布的内容。

有待审时，现有 `content_review_outbox` 同事务增加 CONTENT_REVIEW_INVALIDATED，reasonCode=AUTHOR_DELETION。
载荷仅包含必要 ID、版本、状态和时间，不含正文或凭证；重复 204 不新增记录。
数据库 CHECK 显式要求存在失效审核时审核前后版本非空，避免 SQL UNKNOWN 绕过约束。
只保存 Outbox，未新增投递器、消费者或文件清理任务。

V22 已在本机执行，V1～V21 保持原样；已执行迁移不得回写或删除。没有新增依赖。
固定错误契约覆盖第 41 个操作，原 40 个操作及原始 openapi.json 保留。

## 独立限流

`app.content.deletion` / `{content-deletion}` 与撤回、重新公开、公开浏览及查询额度隔离。
默认来源 120 次/分钟、用户 60 次/分钟，Redis Lua 原子检查两个窗口。
合法重复 204 也计数，业务失败不退还已用额度；429 带正整数 Retry-After。
Redis 故障返回 503，不进入删除事务。

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| ip-per-minute-limit | CONTENT_DELETION_IP_PER_MINUTE_LIMIT | 120 |
| user-per-minute-limit | CONTENT_DELETION_USER_PER_MINUTE_LIMIT | 60 |
| redis-key-prefix | CONTENT_DELETION_REDIS_KEY_PREFIX | fc:content: |

配额必须为正整数，前缀不能为空或含花括号；非法配置阻止启动。真实 infra/.env 保留，环境变量样例已追加。
真实验证记录见[开发与验证](development.md)。
