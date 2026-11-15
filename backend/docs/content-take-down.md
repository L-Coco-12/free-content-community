# 运营强制下架并记录处置

[返回后端入口](../README.md) · [后台详情](content-admin-detail.md) · [审核](content-review.md) · [恢复作品](content-restoration.md)

`POST /api/v1/admin/contents/{contentId}/take-downs` 对应 `takeDownContent`。

```http
POST /api/v1/admin/contents/content_1/take-downs
Authorization: Bearer <审核员或管理员凭证>
Idempotency-Key: <客户端生成的16～128字符，重试复用>
Content-Type: application/json

{"expectedVersion":7,"reasonCode":"SPAM","reason":"经核对存在违规营销，需整改后重审。"}
```

成功 201 + `ApiResponse<ModerationAction>`，返回处置 ID、CONTENT 目标和内容 ID、TAKE_DOWN、完整原因、操作人员、时间和 resultingVersion。
不返回工作区，后续可重新读取后台内容详情；本次不实现 P1 处置查询接口。
响应 X-Request-Id、Cache-Control=no-store；不接受查询参数及未知 JSON 字段。

## 权限和输入

仅 MODERATOR/ADMIN，账号须 ACTIVE、邮箱已验证，操作员状态和 authVersion 在领取及完成时都重新检查。
不要求 USER 创作角色，也不额外禁止处理本人内容；审批接口的禁止自审规则保持原样。
WRITE_BLOCKED/DISABLED 均不能下架，即使后台只读查询仍允许 WRITE_BLOCKED。
统一后台授权入口只登记本次 POST 路径，其他方法和未实现子路径继续拒绝。

- contentId：1～64 位字母、数字、下划线或短横线。
- expectedVersion：必填整数，1～2147483647，来自最近工作区；实际处置时须匹配当前内容版本。
- reasonCode：必填 SPAM/ABUSE/COPYRIGHT/OTHER，严格匹配枚举名称。
- reason：必填、非全空白，1～1000 个 Unicode 码点，保留原文，不 trim。
- Idempotency-Key：必填单个请求头，16～128 字符。

类型、JSON 格式、未知字段、重复或缺失请求头、查询参数错误返回 400；字段缺失/null、原因空白/越长、版本或 ID/键长度不合法返回 422。
不支持 Content-Type 返回 415；格式不合法不领取记录、不消耗配额。

## 实际下架与新请求状态

适用有历史 APPROVED 发布指针的 PUBLISHED/WITHDRAWN 内容，包括 ACTIVE 和 DELETED。
没有历史发布稿的 UNPUBLISHED 内容返回 409 CONTENT_NOT_EDITABLE。
已经 TAKEN_DOWN 时，新键请求返回同一状态错误，不新增处置，也不终结后来新建的整改草稿或审核。
同键成功重放先于新执行的状态、版本检查。
合法 ID 不存在返回 404；实际处置版本过期或达到上限返回 CONTENT_VERSION_CONFLICT，附 currentVersion。

实际变更在同一事务中：

1. publicationStatus=TAKEN_DOWN，内容 version 增加一次，updatedAt 更新。
2. lifecycle 和 deletedAt 原样保留，回收站内容仍在回收站。
3. 当前 DRAFT 或 IN_REVIEW 修订变为 CANCELLED，保留标题、正文、引用和创建时间。
4. 当前 PENDING 审核变为 INVALIDATED，审核版本增加一次，decidedAt 更新，reviewerId/reason 清空。
5. 清空 workingRevisionId、activeReviewId，保留历史发布指针、原 APPROVED 修订及首次/最近发布时间。
6. 保存不可变处置、最小审计、作者站内通知、公开失效事件、必要的审核失效事件及 SUCCEEDED 幂等结果。

发布指针缺失但状态声称已经发布、修订/工作/审核绑定不一致或审核版本无法递增等完整性问题返回 503，不部分下架或伪造正常结果。
不检查 MinIO 对象，不要求素材 READY，不删除历史或素材，不更改作者账号状态。
新的公开详情、列表、作者公开数量及媒体取址依据 TAKEN_DOWN 拒绝或过滤；既有短期 URL 仍有原到期窗口。

## 整改与处置重放

作者必须新建修订、整改并提交 REMEDIATION 审核，审核通过后系统自动发布新稿并解除下架标记。
删除后恢复仍保留 TAKEN_DOWN；重新公开旧批准稿返回 REMEDIATION_REQUIRED。
真实集成测试串联新建草稿、提交整改、审批发布；原下架请求成功重放仍只返回原处置，不将新公开版本再次下架。

幂等作用域为调用者 + takeDownContent + key，24 小时有效；指纹包括 contentId、规范化版本、原因分类及原文原因。
同键同请求返回首次 201 处置 ID 和结果，不重复版本、通知或事件，不消耗配额。
同键换内容、版本、分类或原因返回 IDEMPOTENCY_KEY_REUSED；处理中返回 REQUEST_IN_PROGRESS 和 Retry-After。
重放仍验证当前资格，校验成功快照与不可变处置事实一致；损坏记录返回 503。
24 小时过期可作为新意图使用，但旧处置、审计、通知及事件保留。重放不延长有效期。

租约为 60 秒，过期可接管。完成时再次验证令牌、租约及有效期；旧请求不能提交或删除替换后的租约。
失败尝试释放自己的预留记录；清理失败保留 PROCESSING 租约，之后按过期接管恢复。
账号采用共享资格锁，同键空记录并发由唯一键保护，唯一键竞争只重读一次，不能无限循环。

## 调用链和锁顺序

Controller、DTO、Service/impl、事务、Mapper、配置、限流及测试都在 content.moderation。

1. `ContentTakeDownController#takeDown`：严格 JSON/请求头/查询检查，返回 201 处置。
2. `ContentTakeDownServiceImpl#takeDown`：路径及键校验、请求指纹、领取/重放、独立配额、完成及失败释放。
3. `ContentTakeDownTransactionService#claim`：READ_COMMITTED 短事务中保护当前资格，领取、接管或重放幂等结果。
4. Redis 配额在数据库事务外执行。
5. `ContentTakeDownTransactionService#finish`：重新授权及验证租约，锁定内容和工作链，原子完成所有业务写入。
6. `notification.service.ModerationNotificationWriter#create`：MANDATORY 加入调用方事务，写作者通知，不另开事务或投递外部消息。

完成事务按操作员账号/角色共享锁 → 幂等记录 → 通知收件人账号共享锁 → 内容排他锁 → 待审记录 → 工作修订 → 历史发布修订执行。
收件人账号在内容之前锁定，避免通知外键与作者“账号→内容”的写入锁序相反；内容加锁后再次核对作者身份。
实际验证两个操作员交叉处置对方作品、同键空记录竞争、多人争用同一版本及与真实审批并发。
下架先提交时旧审批版本冲突；审批先提交时旧下架版本冲突，运营人员须重新读取确认后再处置。

## 处置、通知、Outbox 与迁移

V24 新增：

- content_moderation_action：本次内容下架的不可变事实，完整保留原因分类、原因、人员及结果版本，按内容/结果版本唯一。
- content_take_down_request：独立幂等租约与成功快照，成功结果绑定处置和同一内容。
- content_take_down_audit：记录原发布修订、被取消的工作修订及原状态、失效审核及前后版本、requestId 和时间。
- user_notification：作者未读 MODERATION_RESULT，目标 CONTENT/contentId，以接收人 + 来源事件 + 类型去重。

通知只拼接处置原因摘要，不复制未审核稿件正文或作者账号资料；最多 500 个码点并保留省略号，完整 1000 码点原因保留在处置事实中。
通知记录与业务写入同事务，不意味着浏览器、邮件或 MQ 已收到通知。本次未实现通知列表/已读接口或消息投递器/消费者。
content_publication_outbox 扩展 CONTENT_TAKEN_DOWN，原类型保留；待审时 content_review_outbox 增加 CONTENT_REVIEW_INVALIDATED，reasonCode=MODERATION_TAKE_DOWN。
Outbox 为 PENDING，载荷只包含必要 ID、状态、版本和时间，不复制完整正文或原因。
审计 CHECK 显式排除条件必填字段为 NULL，不能因 SQL UNKNOWN 丢失审核版本证据。

V24 已在本机执行，V1～V23 保持原样；所有已执行迁移均不得回写。无新增依赖，真实 infra/.env 保留。
固定错误契约新增第 45 个操作，原 44 个操作及原始 openapi.json 保留。验证记录见[开发与验证](development.md)。

## 独立限流

app.content.take-down / {content-take-down}：来源 60 次/分钟、操作人员 100 次/小时；成功幂等重放免配额。
与审核决定及后台查询隔离；格式非法不计数，新执行的业务失败不退还配额，429 带 Retry-After，Redis 故障返回 503 且不执行下架。

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| ip-per-minute-limit | CONTENT_TAKE_DOWN_IP_PER_MINUTE_LIMIT | 60 |
| user-hourly-limit | CONTENT_TAKE_DOWN_USER_HOURLY_LIMIT | 100 |
| redis-key-prefix | CONTENT_TAKE_DOWN_REDIS_KEY_PREFIX | fc:content: |

配额须为正整数，前缀不能为空或含花括号，非法配置阻止启动。
