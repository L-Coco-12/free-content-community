# 从回收站恢复本人作品

[返回后端入口](../README.md) · [工作区](content-workspace.md) · [移入回收站](content-deletion.md) · [重新公开](content-republication.md)

`POST /api/v1/creator/contents/{contentId}/restorations` 对应 `restoreMyContent`。

```http
POST /api/v1/creator/contents/content_1/restorations
Authorization: Bearer <用户凭证>
Idempotency-Key: <客户端生成的16～128字符，重试复用>
Content-Type: application/json

{"expectedVersion":7}
```

成功 200 + `ApiResponse<ContentWorkspace>`，无 Location；只接受 JSON 中的 expectedVersion，不接受查询参数及未知字段。
contentId 为 1～64 位字母、数字、下划线或短横线；expectedVersion 为 1～2147483647 的整数。
缺失或重复幂等头、JSON 格式/类型错误及未知字段返回 400；ID、幂等头长度、版本范围或必填字段不合法返回 422。
需要有效 Bearer、邮箱已验证、ACTIVE 账号、USER 创作资格及本人资源，管理角色不扩大本人范围。

## 恢复状态与保留的数据

只对 DELETED 内容执行新的恢复；已 ACTIVE 的新请求返回 409 CONTENT_NOT_EDITABLE。
内容不存在或不是本人返回 404，不返回 currentVersion。
实际恢复须匹配 expectedVersion，过期或版本达到上限返回 409 CONTENT_VERSION_CONFLICT，并附 currentVersion。

| 删除时的情况 | 恢复结果 |
| --- | --- |
| 没有历史发布指针 | ACTIVE + UNPUBLISHED |
| 有历史发布指针，原为 PUBLISHED/WITHDRAWN | ACTIVE + WITHDRAWN |
| 原为 TAKEN_DOWN | ACTIVE + TAKEN_DOWN，下架标记保留 |

清除 deletedAt，更新 updatedAt，内容版本增加一次。原发布指针、首次和最近发布时间、普通 DRAFT、冻结修订和引用保留。
恢复不自动公开，不更改素材就绪状态，也不复活旧的 PENDING 审核或 CANCELLED 修订。
存在残留 activeReviewId、PENDING、IN_REVIEW，或工作指针不一致时返回 503，避免把不完整的删除结果恢复到正常工作区。

恢复后的公开详情、列表、作者公开数量和新的公开媒体取址仍按公开条件拒绝或过滤。
普通作品可另调用重新公开接口；TAKEN_DOWN 继续要求新整改稿审核通过。
没有历史发布稿的作品应继续编辑并走首次审核，不能通过恢复或重新公开接口跳过审核。

## 24 小时幂等与租约

独立 `content_restoration_request` 按用户和键摘要隔离，不与新建内容、建草稿或其他操作共用幂等记录。
请求指纹包含 contentId 和规范化 expectedVersion；只存键摘要，不存明文幂等键。

- 同键同请求成功重放：先复核当前身份及内容归属，再返回首次成功的工作区快照，不重复恢复、加版本、写审计或事件，也不消耗配额。
- 同键不同内容或版本：409 IDEMPOTENCY_KEY_REUSED。
- 同键仍由有效租约处理：409 REQUEST_IN_PROGRESS，并提供正整数 Retry-After。
- 24 小时过期：允许作为新意图使用，旧业务审计和事件保留；重放不会延长有效期。

快照只保存业务 data，响应 requestId 和 serverTime 属于当前请求。
成功快照不代表当前状态：例如恢复成功后再次删除，重放旧恢复请求仍返回原 ACTIVE 快照，但数据库保持后来删除的状态。
同样，后来已重新公开时，旧恢复重放不会把它重新撤回。调用方需要当前状态时重新读取工作区。

处理租约为 60 秒；过期可用新令牌接管。finish 再检查记录、令牌、租约和键有效期，旧请求不能提交，也不能释放替换后的新租约。
业务或限流失败会尝试释放自己的 PROCESSING 记录；清理失败时保留租约，之后按过期接管恢复，不能把预留记录当成成功。
损坏或绑定不一致的成功快照返回 503，不返回伪造工作区，也不重新执行恢复。

## 调用链与原子事务

1. `ContentRestorationController#restore`：严格解析 JSON，检查查询和幂等头，返回 200 工作区。
2. `ContentRestorationServiceImpl#restore`：身份、路径和键长度校验，计算指纹，领取或读取幂等记录；成功重放直接返回，否则获取配额并完成恢复。
3. `ContentRestorationTransactionService#claim`：READ_COMMITTED 短事务中锁定账号和角色、领取或接管幂等租约，或读取成功快照。
4. Redis 限流在数据库事务外执行。
5. `ContentRestorationTransactionService#finish`：再次核对资格和租约，锁定内容及工作链，更新状态，读取恢复后的工作区，并保存审计、事件和成功快照。

finish 的状态变化、最小审计、Outbox 与 SUCCEEDED 快照在同一事务提交，任何一步失败全部回滚。
锁顺序保持账号/角色 → 幂等记录 → 内容 → 待审检查 → 工作修订。
同键并发只会执行一次恢复，另一请求获得成功重放或 REQUEST_IN_PROGRESS；不同键争用同一内容只允许一次实际恢复。
旧 DELETE 与恢复并发时，删除在恢复之前可无副作用成功，恢复之后则因旧版本拒绝，不能误删新恢复的版本。

## 审计、事件与迁移

V23 新增 `content_restoration_request` 和 `content_restoration_audit`，后者记录作者、内容、可空历史发布修订、前后发布状态、前后内容版本、requestId 与时间。
恢复审计按内容和变更后版本唯一；数据库 CHECK 保证 TAKEN_DOWN 不能通过恢复审计记录变成其他状态。
已有 `content_lifecycle_outbox` 增加 CONTENT_RESTORED 类型，保留 CONTENT_DELETED；事件为 PENDING，仅含必要 ID、版本、状态和时间。
本任务只保存待发送事件，未实现投递器、消费者或其他接口。

V23 已在本机执行，V1～V22 和真实 infra/.env 保持原样；所有已执行迁移不得回写或删除。没有新增依赖。
固定错误契约新增第 42 个操作，原 41 个操作及原始 openapi.json 保留。

## 独立限流

`app.content.restoration` / `{content-restoration}` 独立于删除、建草稿和其他业务窗口。
默认来源 60 次/分钟、用户 100 次/小时；Redis Lua 原子检查两个窗口，成功幂等重放免配额。
非法参数不计数，进入新执行的业务失败不退还已用额度。429 带 Retry-After；Redis 故障返回 503，恢复不执行。

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| ip-per-minute-limit | CONTENT_RESTORATION_IP_PER_MINUTE_LIMIT | 60 |
| user-hourly-limit | CONTENT_RESTORATION_USER_HOURLY_LIMIT | 100 |
| redis-key-prefix | CONTENT_RESTORATION_REDIS_KEY_PREFIX | fc:content: |

配额须为正整数，前缀非空且不能含花括号；错误配置阻止启动。验证结果见[开发与验证](development.md)。
