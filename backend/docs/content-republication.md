# 重新公开最近批准稿

[返回后端入口](../README.md) · [工作区](content-workspace.md) · [撤回公开](content-publication-withdrawal.md) · [审核](content-review.md)

`PUT /api/v1/creator/contents/{contentId}/publication` 对应 `republishApprovedContent`。

```http
PUT /api/v1/creator/contents/content_1/publication
Authorization: Bearer <用户凭证>
Content-Type: application/json

{"expectedVersion":8,"revisionId":"revision_approved"}
```

需要邮箱已验证、账号 ACTIVE、USER 创作资格和本人内容；成功返回 200 + `ApiResponse<ContentWorkspace>`。
两个字段均必填，不要求 Idempotency-Key，不接受查询参数或额外 JSON 字段。
expectedVersion 为 1～2147483647 的整数，revisionId 与 contentId 为 1～64 位字母、数字、下划线或短横线。
类型或 JSON 格式错误返回 400；缺失、null、越界和 ID 格式错误返回 422。

## 状态和修订选择

事务内重新核对账号资格和 authVersion，锁定本人内容后处理：

| 当前状态 | 行为 |
| --- | --- |
| lifecycle=DELETED | 409 CONTENT_NOT_EDITABLE |
| publicationStatus=TAKEN_DOWN | 409 REMEDIATION_REQUIRED，不能解除运营下架 |
| UNPUBLISHED | 409 CONTENT_NOT_EDITABLE，不能用此接口首次发布 |
| WITHDRAWN | 复核最近批准稿及公开条件，校验 expectedVersion 后恢复公开 |
| PUBLISHED，已经是请求的最近批准稿 | 复核公开条件后无副作用返回当前工作区，允许旧 expectedVersion |
| PUBLISHED，请求替换为另一批准稿 | 409 CONTENT_NOT_EDITABLE |

revisionId 必须属于本内容，且是 revisionNumber 最大的 APPROVED 修订。
较新的 DRAFT、IN_REVIEW、REJECTED 或 CANCELLED 不取代最近批准稿；不能任意回退到旧批准版本。
其他人的内容、不存在的内容或不属于本内容的修订统一 404，不返回 currentVersion。

新修订审批通过仍由审批流程自动发布。本接口不创建修订或审核，也不更改冻结稿正文、标题或引用。
已经存在的普通草稿、IN_REVIEW 修订和 PENDING 审核原样保留。
回收站内容须先通过[恢复作品](content-restoration.md)回到 ACTIVE/WITHDRAWN，才能通过本接口重新公开；本接口只改变公开状态。

## 当前公开条件和时间

复用 `PublicContentAssembler` 的公开投影规则：素材必须 READY、归作者且用途与内容类型匹配，VIDEO 必须有关联视频。
公开规则继续保留已引用的 DISABLED 标签。批准稿当前不可公开返回 409 CONTENT_NOT_EDITABLE；发布指针、时间或正文等完整性异常返回 503。
本接口没有 MEDIA_NOT_READY 错误码，不透传审核用例的专用错误。

`PublicContentVisibilityReader` 使用 MANDATORY 加入已有事务，按序读取引用、锁定去重排序的素材及标签，锁持有至提交。
同版重复请求也检查当前公开条件。本接口不访问对象存储、不执行 HEAD、不签发 URL；媒体读取地址仍由公开媒体取址接口核验并签发。

实际重发时只更新内容聚合：publicationStatus=PUBLISHED、publishedRevisionId=选定批准稿、version+1、updatedAt 和 publishedAt=本次 UTC 时间。
firstPublishedAt 保持原值，因此全站和作者列表仍按首次公开时间排序，旧作品不会因重发置顶。
内容版本不匹配或已达上限返回 CONTENT_VERSION_CONFLICT，并附 currentVersion；客户端须重新读取工作区。
已公开同版的无副作用成功不要求递增，因此内容版本达到上限时仍可返回当前工作区。

## 调用链和原子提交

1. `ContentRepublicationController#republish`：解析并严格校验 JSON，拒绝查询参数，返回当前工作区。
2. `ContentRepublicationServiceImpl#republish`：检查身份和路径、获取独立配额、调用事务，数据库及事务故障转换为 503。
3. `ContentRepublicationTransactionService#republish`：READ_COMMITTED 事务中锁定账号及角色、本人内容和修订，检查最近批准稿及公开条件。
4. `ContentRepublicationMapper`：以归属、ACTIVE/WITHDRAWN 和聚合版本条件更新内容，保存审计和 Outbox，再读取当前工作区。

所有内容写入都通过同一聚合锁串行化。与审批或创建草稿竞争同一版本时，仅一方实际变更；另一方重新检查状态、修订或版本后失败。
两个同版重发请求可以都成功，只发生一次实际变更。撤回的旧请求不会撤回刚重新公开的新版本。

内容变更、最小审计、待发送事件及返回工作区读取属于同一事务；任一持久化或读取步骤失败，整体回滚。
审计 `content_publication_republish_audit` 记录作者、内容、批准修订、前后聚合版本、requestId 和发生时间，按内容与变更后版本唯一。
`content_publication_outbox` 写入 PENDING 的 CONTENT_REPUBLISHED，按内容、聚合版本和事件类型唯一，支持多次撤回/重发周期。
事件仅包含必要 ID、版本、状态和时间，不含正文或凭证。
同版重复成功不改变日期、业务版本、审计或事件；返回当前工作区，不是首次成功快照。
本任务仅持久化 Outbox，未实现投递器或消费者。

## 独立限流和迁移

配置 `app.content.publication-republish`，Redis 作用域 `{content-publication-republish}`。
默认来源 120 次/分钟、用户 60 次/分钟，两个窗口以 Redis Lua 原子检查和计数，与撤回、公开浏览等操作隔离。
合法的重复成功也计数；参数非法不计数，业务失败不退还已用额度。429 附正整数 Retry-After，Redis 故障返回 503 且不进入业务事务。

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| ip-per-minute-limit | CONTENT_PUBLICATION_REPUBLISH_IP_PER_MINUTE_LIMIT | 120 |
| user-per-minute-limit | CONTENT_PUBLICATION_REPUBLISH_USER_PER_MINUTE_LIMIT | 60 |
| redis-key-prefix | CONTENT_PUBLICATION_REPUBLISH_REDIS_KEY_PREFIX | fc:content: |

配额须为正整数，前缀非空且不能含花括号；非法配置阻止启动。
V21 新建重发审计表，并扩展发布 Outbox 类型以保留 CONTENT_WITHDRAWN、允许 CONTENT_REPUBLISHED。
V21 已在本机执行；V1～V20 保持原样，所有已执行迁移不得回写或删除。没有新增依赖，也没有修改真实 infra/.env。
固定错误契约新增第 40 个操作，原 39 个操作及原始 openapi.json 保留。验证结果见[开发与验证](development.md)。
