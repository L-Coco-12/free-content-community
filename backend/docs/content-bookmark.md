# 收藏内容

[点赞](content-like.md) · [公开列表](content-list.md) · [结构](../STRUCTURE.md)

`PUT /api/v1/me/bookmarks/{contentId}`需要Bearer身份，无请求体、查询参数、expectedVersion或
必需Idempotency-Key。成功204空响应，附X-Request-Id及no-store。语义是设为已收藏，不是切换。

## 写入

代码归content.interaction，URL位于/me不代表迁入auth。Controller调用ContentBookmarkService
接口/impl，事务通过AccountAccessService复核认证版本、ACTIVE及当前邮箱验证状态，不使用
canCreate、作者归属或审核角色作为收藏门槛；可以收藏本人已公开作品。

限流在SQL事务外。事务内按账号→当前内容/公开修订/排序后的素材和标签→收藏关系加锁，
复用PublicContentVisibilityReader.requireCurrentlyVisible，与点赞保持相同公开口径：
生命周期ACTIVE、PUBLISHED及APPROVED发布稿、正确归属/用途/READY的引用；停用标签保留，
审核中的新稿不影响已有公开稿。即使关系已存在，重复请求也先复核资格和当前可见性。

V27新增content_bookmark，服务器生成稳定ID，(user_id,content_id)唯一。created_at与id
保留给后续收藏列表的倒序键集分页，重复不更新、不重建记录、不改变顺序。收藏绑定作品ID，
换公开修订仍保留同一收藏事实。作品version、updated_at、发布指针及审核状态不受影响。

普通INSERT成功后提交。表既有ID主键也有用户/作品唯一键，重复键异常后以当前读确认目标
关系确实存在；不能把无关ID碰撞误报为成功。其他写入失败回滚并503，不用INSERT IGNORE掩盖。
软删除、撤回或下架保留事实，阻止新的或重复的PUT；目标物理清理时级联关系。
用户外键限制直接删除，未来账号清理需先处理其关系。V1～V26未改写，无新依赖。

## 标记回显

详情、首页和作者列表通过ContentBookmarkReader在调用方相同快照内批量读取viewerHasBookmarked。
访问者ID来自会话，不从请求参数或游标猜测。不修改点赞统计；点赞和收藏标记彼此独立。
游客固定false且不查询收藏表；登录用户每页只增加一次查询。非空全站列表游客6次、登录7次
业务SQL，不按卡片循环查询。读取依赖故障返回503，不静默变成false或空列表。

当前DTO没有收藏计数，本次不新增计数或统计字段。[取消收藏DELETE](content-unbookmark.md)已接通，
[本人收藏列表GET](content-bookmark-list.md)已接通，按收藏关系时间和ID倒序，仅返回当前可见作品。

## 配额与错误

配置归app.content.bookmark，由ContentModuleConfiguration注册：

| 环境变量 | 默认值 |
| --- | --- |
| CONTENT_BOOKMARK_IP_PER_MINUTE_LIMIT | 120/分钟 |
| CONTENT_BOOKMARK_USER_PER_MINUTE_LIMIT | 60/分钟 |
| CONTENT_BOOKMARK_REDIS_KEY_PREFIX | fc:content: |

收藏PUT与取消DELETE共享独立{content-bookmark}键，与点赞/取消及公开查询隔离；重复204也计数，数据库失败不自动退还。
401身份无效，403账号受限或资格不足，404目标当前不可见，422 ID错误，400额外输入，
429附Retry-After；数据/限流依赖故障503。重复不使用409，也不需要业务幂等键。

ContentBookmarkFlowIT使用真实HTTP/MySQL/Redis验证去重、并发、资格复核、可见性、新稿审核、
换公开修订、事务回滚、ID碰撞、撤回竞争、独立限流、点赞互不干扰与当前访问者批量标记。
配置和服务单元测试验证启动校验与错误隔离；错误快照新增外部契约已有bookmarkContent定义。
