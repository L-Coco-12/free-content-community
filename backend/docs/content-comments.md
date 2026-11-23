# 评论与回复

## 发布评论和回复

`POST /api/v1/contents/{contentId}/comments` 对应 `createComment`。邮箱已验证的 ACTIVE 用户可对当前公开作品发表评论。
请求使用 JSON，`body` 为 1～2000 个 Unicode 码点的纯文本，保存前去除首尾空白。省略 `replyToCommentId` 创建顶级评论。
请求体的传输上限为 32 KiB。成功返回 `201`、评论数据和 `Location: /api/v1/comments/{commentId}`。

回复目标必须是同一作品中的可见评论。回复顶级评论时，`rootCommentId` 指向该顶级评论。回复楼中楼时沿用其
`rootCommentId`，并用 `replyToCommentId` 保存实际回复目标。隐藏或删除的评论不可直接回复，原楼层下仍可见的回复仍可作为目标。

必填 `Idempotency-Key` 长度 16～128 个字符。服务以发起人和本接口为范围，保存评论路径、规范化正文及回复目标的指纹，
并在 24 小时内复用原结果。同键异参返回 `409 IDEMPOTENCY_KEY_REUSED`，同一请求处理中返回
`409 REQUEST_IN_PROGRESS` 和 `Retry-After`。重放会复核账号、作品及已创建评论的当前可见性。

V32 保存评论与幂等记录。回复他人时，使用回复 ID 作为通知的事件及目标 ID，在同一事务保存 `COMMENT_REPLY` 站内通知。
通知使用固定提示文字。自回复免发通知。评论、通知或幂等结果写入失败时一并回滚，成功后可重放原快照。

评论发布与删除共享 `app.content.comment` 写入配额，默认每用户 60 次/分钟、每来源 120 次/分钟，超限返回
`429` 和 `Retry-After`。公开详情和卡片的 `commentCount` 统计当前可见的顶级评论及回复，卡片按页批量查询。

## 读取公开评论

`GET /api/v1/comments/{commentId}` 对应 `getPublicComment`，可直接访问发布成功返回的 `Location`。
游客可读取，携带 Bearer Token 时必须验证。请求仅接收路径参数，成功返回 `200` 与 `Comment`。

作品必须满足当前公开可见条件。顶级评论的 `replyCount` 统计同一楼层的可见回复，包括楼中楼。回复自身的
`replyCount` 为 0。删除或隐藏的顶级评论仍有可见回复时返回 `DELETED_PLACEHOLDER`，其中
`author` 和 `body` 为 `null`。缺失评论、不可见作品、不可见回复或没有可见回复的不可见根楼层均返回 `404`。

评论、作品可见性、回复数量及作者的公开资料使用同一个只读事务快照。读取限流独立配置在
`app.content.comment-query`，默认每来源 120 次/分钟，已登录用户额外有每用户 60 次/分钟的配额。

## 浏览顶级评论

`GET /api/v1/contents/{contentId}/comments` 对应 `listContentComments`。游客可读取，携带 Bearer Token 时必须有效。
作品当前公开时返回 `200` 与 `CommentPage`。可见顶级评论列出正文和作者公开资料，隐藏或删除的顶级评论仍有可见回复时
返回 `DELETED_PLACEHOLDER`，正文与作者为 `null`。同楼层的可见回复计入 `replyCount`。

仅接受 `cursor` 和 `limit`，默认每页 20 条，范围 1～50。顶级评论按创建时间、ID 倒序。首批生成
`snapshotAt` 作为根评论的创建时间上界。后续页重新核对作品、根评论和回复的可见性。
游标绑定接口、作品、游客或用户身份和页大小，沿用公开内容游标的密钥与有效期，默认 15 分钟。
有后续页时用 `nextCursor` 继续传入相同的 `limit`。

同一只读事务核对作品、楼层、回复及作者资料。V33 增加根楼层倒序扫描索引，作者公开资料及作品数按页批量查询。
列表与评论详情共享 `app.content.comment-query` 配额。

## 浏览楼层回复

`GET /api/v1/comments/{commentId}/replies` 对应 `listCommentReplies`。游客可读取，携带 Bearer Token 时必须有效。
路径中的 `commentId` 指顶级评论。可见根评论和仍有可见回复的占位根评论均可读取楼内回复。作品不可见、楼层不存在、
传入回复 ID，或隐藏及删除的根评论已无可见回复时，返回 `404`。

同楼层可见回复按创建时间和 ID 正序返回。楼中楼保留 `rootCommentId` 及实际回复目标 `replyToCommentId`。
响应沿用 `CommentPage`，默认每页 20 条，范围 1～50。游标绑定回复接口、顶级评论、访问身份和页大小，
以首批 `snapshotAt` 为回复时间上界，默认有效 15 分钟。后续页重新检查当前作品和回复的可见性。

查询复用 V32 的楼层回复索引，作者资料按页批量读取，使用与顶级评论列表、详情相同的 `app.content.comment-query` 配额。

## 删除本人评论

`DELETE /api/v1/comments/{commentId}` 对应 `deleteMyComment`。已验证邮箱的 ACTIVE 用户可删除自己的评论或回复，
请求仅接收路径参数。评论不存在或属于其他用户均返回 `404`。

`VISIBLE` 或 `HIDDEN` 的本人评论会标记为 `DELETED`，所属作品下架后仍可删除。实际变化时递增 `version`
并更新 `updatedAt`，成功和重复删除均返回空响应体的 `204`。同一评论的并发删除仅发生一次状态变更。

原记录与楼内回复保留。删除顶级评论且仍有可见回复时，公开接口返回正文与作者均为 `null` 的占位。
删除回复后，该回复从公开列表移除，仍可见的楼中楼保留。详情和列表通过当前可见记录计算评论及回复数量。

## 运营查询评论

`GET /api/v1/admin/comments` 对应 `listAdminComments`。邮箱已验证的审核员和管理员在具备
`comment:moderate:read` 权限时可查询。写入受限的运营账号仍可读取。

可选的 `contentId`、`authorId`、`state` 筛选同时生效，状态取值为 `VISIBLE`、`HIDDEN`、`DELETED`。
运营投影返回原始纯文本、内部状态和版本，可查询已隐藏或删除的评论、回复，以及所属作品当前不可见的记录。

列表按创建时间和 ID 倒序，`page` 默认 1，`pageSize` 默认 20，范围 1～100。
`total` 按同一筛选条件统计，空结果和超出范围的页返回空 `items`。运营资格复核、总数和当前页在同一只读事务内执行。
V34 增加运营列表的默认排序索引。查询使用独立的 `app.content.comment-admin-query` 配额，默认每来源
120 次/分钟、每运营账号 60 次/分钟。

## 运营读取指定评论

`GET /api/v1/admin/comments/{commentId}` 对应 `getAdminComment`，仅接收路径参数。具备
`comment:moderate:read` 权限的审核员和管理员可按主键读取单条评论。评论不存在返回 `404`。

详情沿用运营列表的 `AdminComment` 投影，返回当前原文、内部状态和版本。所属作品是否公开不影响授权运营读取。
接口在只读事务内再次校验运营资格，与运营评论列表共享 `app.content.comment-admin-query` 配额。

## 隐藏违规评论

`POST /api/v1/admin/comments/{commentId}/hides` 对应 `hideComment`。邮箱已验证的 ACTIVE 审核员或管理员
具备 `comment:hide` 权限时可执行。请求须提供 `Idempotency-Key`、`expectedVersion` 和 `reason`，
原因长度 1～1000 个 Unicode 码点，保存前去除首尾空白。成功返回 `201` 与 `ModerationAction`。

事务锁定目标评论并复核版本。版本冲突返回 `409 RESOURCE_VERSION_CONFLICT` 及 `currentVersion`。
版本匹配且评论处于 `VISIBLE` 时将状态改为 `HIDDEN`，版本加一。其他状态返回 `409 COMMENT_STATE_CONFLICT`。
原评论记录和后代回复保留。仍有可见回复的根评论通过公开接口显示为占位。

V35 的 `comment_moderation_action` 保存处置与审计信息，包括操作者、原因、前后状态与版本、请求编号和执行时间。
评论状态、处置和幂等结果在同一事务提交。幂等键按操作者及本接口作用域生效 24 小时，成功重放复用原处置 ID 和版本。
重放仍校验权限。同键异参返回 `IDEMPOTENCY_KEY_REUSED`，执行中的同键请求返回 `REQUEST_IN_PROGRESS` 及 `Retry-After`。

隐藏使用独立的 `app.content.comment-hide` 写入限流，默认每运营账号 60 次/分钟、每来源 120 次/分钟。
