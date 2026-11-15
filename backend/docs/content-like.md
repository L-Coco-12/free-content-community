# 点赞

[公开内容](content-browse.md) · [结构](../STRUCTURE.md)

`PUT /api/v1/contents/{contentId}/likes/me`需要Bearer身份，仅接收路径contentId，无请求体、
查询参数、expectedVersion或必需的Idempotency-Key。成功204空响应，附requestId与no-store。
语义为设为已点赞，重复请求不取消点赞，也不重复计数。作者可以给自己的公开作品点赞。

## 写入与权限

代码归content.interaction，Controller→ContentLikeService/impl→事务组件→Mapper。
身份来自会话，限流在SQL事务外执行；事务通过AccountAccessService复核认证版本、ACTIVE及
当前邮箱验证状态。不以canCreate或作者归属作为点赞条件，审核员、管理员也能正常互动。

PublicContentVisibilityReader.requireCurrentlyVisible锁定当前内容、公开修订、引用和排序后的
素材/标签，使用现有公开投影规则。作品必须ACTIVE/PUBLISHED，当前发布指针指向APPROVED稿，
关联素材归属/用途/READY正确。停用标签沿用公开规则保留；正在审核的新稿不影响已有公开稿。
不存在、撤回、下架、删除或不可见素材返回404；公开数据结构损坏可返回503，不能绕过可见性。

锁序为账号共享锁→内容/修订/素材共享锁→关系→统计行。撤回、下架及换发布指针的内容写锁
会与该共享锁串行，不采用先GET详情、再另开事务写入的检查方式。点赞不访问MinIO。

## 一致性与V26

- content_like以(user_id,content_id)为唯一主键，关联作品而不是修订。普通INSERT仅在实际新增
  成功时增加计数；唯一键冲突明确表示已有关系，返回204。未使用INSERT IGNORE掩盖其他错误。
- content_like_stat独立保存数据库计数，首次点赞惰性建立，已有作品无统计行时读0。增量使用
  原子SQL；关系与计数处于同一事务，任一步失败一起回滚。重复点赞仍先复核权限与可见性。
- 统计不改变作品编辑version、updated_at、发布指针或审核状态。发布新修订保留同一作品点赞。
- 内容物理清理会级联清理目标关系与统计；用户外键限制直接物理删除，未来账号清理须显式处理
  关系和计数，不能让用户级联删除关系后遗留错误统计。当前软删除、撤回不清除点赞事实。
- V26新增两表，V1～V25未改写；不引入MQ或新依赖。关系是事实来源，数据库统计同步更新，
  不以Redis缓存或前端本地加减替代持久化。外部人工改库后的计数校准不是本接口的自动任务。

## 读取与限流

详情、首页列表和作者列表通过共享ContentLikeReader在调用方同一只读快照内读取likeCount与
viewerHasLiked；访问者ID由当前会话显式传递，不从请求体或游标文本猜测。游客flag恒false，
总数仍真实返回。非空全站列表游客6次业务SQL，登录用户另有一次批量[收藏](content-bookmark.md)查询，共7次，不按卡片循环查询。
统计读取失败返回503，不能变成成功空列表或假0。评论数等未实现字段保持原约定，收藏标记已单独接通。

配置为app.content.like，由ContentModuleConfiguration注册：

| 环境变量 | 默认值 |
| --- | --- |
| CONTENT_LIKE_IP_PER_MINUTE_LIMIT | 120/分钟 |
| CONTENT_LIKE_USER_PER_MINUTE_LIMIT | 60/分钟 |
| CONTENT_LIKE_REDIS_KEY_PREFIX | fc:content: |

PUT点赞与DELETE取消共享独立{content-like}键，来源与用户窗口原子检查。重复成功、后续事务失败均已消耗获准配额，
不因幂等而免计数，也不自动退还。超限429附Retry-After，Redis无法确认配额时503。
无身份401、账号受限403、非法ID422、额外参数/请求体400、目标不可见404、数据依赖失败503。
接口不以409表示重复点赞或版本冲突。

ContentLikeFlowIT使用真实HTTP/MySQL/Redis验证重复、同人/多人并发、事务回滚、撤回竞争、
资格复核、审核中新稿、换公开修订、素材可见性、独立配额及访客批量回显；单元测试覆盖配置
绑定与服务错误隔离。原始外部OpenAPI不变，本地错误快照新增契约已有的likeContent定义。

DELETE[取消点赞](content-unlike.md)已接通，读取回显复用同一统计。取消仅清理本人关系，允许在内容不可见后执行。
