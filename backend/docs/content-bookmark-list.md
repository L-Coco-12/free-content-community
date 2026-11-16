# 本人收藏列表

`GET /api/v1/me/bookmarks?cursor=...&limit=...` 需要 Bearer 身份，成功返回
`ApiResponse<ContentPage>`，items 是现有 ContentSummary，不增加收藏关系字段或正文。
默认 limit=20，允许 1～50；cursor 最长 4096 字符。未知、重复查询参数及请求正文返回400。
身份由会话确定，不能通过 userId 参数选择其他人的收藏。

## 查询与分页

入口为 content.interaction.MyBookmarksController → MyBookmarksService 接口/impl →
MyBookmarksTransactionService → BookmarkQueryMapper。所有数据读取处于同一 readOnly +
REPEATABLE_READ 事务，账号共享锁复核 authVersion、邮箱验证和禁用状态；WRITE_BLOCKED
仍可读取，不要求创作或后台角色。

按收藏关系的 `(created_at,id)` 倒序键集分页，使用 V27 已有的用户/时间/ID索引，不依赖作品
发布时间。查询本人关系并复用 PublicContentMapper.VISIBLE，只读取当前发布指针的 APPROVED
修订。删除、下架、撤回、未发布及引用素材不可用等内容不返回，收藏事实保持不变。
作者被禁用不隐藏其仍公开的作品。

BookmarkCursor 使用 AES-GCM，复用 app.content.public-cursor 的稳定32字节密钥和默认900秒期限，
独立 AAD 绑定本接口与收藏排序。载荷绑定用户、limit、初始 snapshotAt、expiresAt、最后扫描的
收藏时间和关系ID。相同用户换有效会话可以继续，跨用户、跨接口、改limit、篡改、非规范编码
均400 CURSOR_INVALID；认证正确但到期返回410 CURSOR_EXPIRED。翻页不续期。

snapshotAt 是收藏时间上界，翻页不接纳在上界后新建或取消后重建的关系。它不是跨请求长期保持
的数据库事务：每页重新检查当前可见性，取消收藏不再返回，重新公开的原有收藏可再次被看到。
取 limit+1 判断 hasMore；游标推进到最后扫描的关系，即使某条公开投影损坏被过滤，也不会反复
扫描同一批。允许短页或空页而 hasMore=true，客户端应依 hasMore 继续并按作品ID去重。
hasMore=false 时 nextCursor 严格为 null；不返回 total。

## 投影、依赖与配额

共享 PublicContentSummaryReader 以 MANDATORY 加入调用者快照；全站列表、作者列表与收藏列表
复用该批量装配。一次非空收藏页执行7次业务SQL：候选、素材、标签、作者、作者公开数量、
点赞统计/本人标记、本人收藏标记。空候选页只查候选，不逐项调用详情，不访问MinIO。
卡片头像为稳定后端URL，真实头像取址由独立请求完成。所有批量查询故障返回503；仅纯投影
识别的单条数据问题可过滤，不把数据库故障吞成空页。

app.content.bookmark-query 使用独立 `{content-bookmark-query}` 配额：来源120次/分钟，
用户60次/分钟。CONTENT_BOOKMARK_QUERY_IP_PER_MINUTE_LIMIT、
CONTENT_BOOKMARK_QUERY_USER_PER_MINUTE_LIMIT、CONTENT_BOOKMARK_QUERY_REDIS_KEY_PREFIX
分别覆盖默认值。与收藏写入、点赞、公开浏览及创作查询隔离。
参数或游标非法/过期不计数；合法空页、重复翻页计数，获准后的数据库失败不退还。
429 带正整数 Retry-After；Redis、数据库或事务服务故障返回503。

成功和失败均带 X-Request-Id、Cache-Control: no-store。错误集合与原始契约一致，
不增加404、409或其他业务错误。无新依赖、迁移、审计、Outbox、计数或作品版本变更。
V1～V27及权威OpenAPI保持原样。

MyBookmarksFlowIT 使用真实HTTP/MySQL/Redis覆盖本人范围、关系时间/ID排序、分页快照、
取消与重建、可见性、权限复核、输入、配额、批量故障和并发快照；BookmarkCursorTest 覆盖
用途与账号绑定及固定过期时间。公开/作者列表回归检查共享装配重构。
