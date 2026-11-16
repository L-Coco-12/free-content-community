# 取消本人收藏

[收藏](content-bookmark.md) · [结构](../STRUCTURE.md)

`DELETE /api/v1/me/bookmarks/{contentId}`需要Bearer身份，无请求体、查询参数、expectedVersion
或必需Idempotency-Key。成功204空响应，附X-Request-Id及no-store。

## 语义与权限

只删除当前登录用户与目标作品的收藏关系。未收藏、重复取消、目标ID有效但作品不存在均204，
不返回内容详情或借状态泄露其是否存在。撤回、下架、移入回收站、公开指针或素材异常都不阻止
清理本人已有关系；不调用公开可见性、修订或素材校验，不访问MinIO。

资格与PUT共用：事务内AccountAccessService共享锁复核认证版本、ACTIVE与当前邮箱验证，
不使用canCreate、作者归属或审核角色作为门槛。401身份无效、403账号受限或资格不足、
422 ID错误、400额外输入、429配额耗尽、503依赖故障。契约不包含404和409。

## 事务与并发

复用content.interaction的收藏Controller、Service、事务与Mapper。锁序为账号→内容主行
共享锁→本人收藏记录；内容锁仅确认ID，不判断公开状态，与PUT/物理清理保持一致顺序。
目标不存在时外键保证不会遗留有效关系，可直接204。删除按(user_id,content_id)执行，
只有0或1行结果合法。删除异常回滚，保留原记录ID与创建时间，不静默报告成功。

当前没有收藏计数字段，不修改点赞统计，也不修改作品version、updated_at、公开指针或审核。
相同关系的记录锁使PUT/DELETE串行：未提交收藏之后的取消最终清除关系；未提交取消之后的
重新收藏最终生成新关系。并发请求按实际数据库执行顺序生效，不按客户端点击时间保证顺序。
取消后重新收藏生成新的ID与created_at，供后续收藏列表排序；重复PUT仍保留原记录。

## 配额与回显

PUT与DELETE共享app.content.bookmark及{content-bookmark}键，来源120次/分钟、用户60次/分钟，
重复204也计数，获准后的数据库失败不自动退还。与点赞/取消点赞及公开查询隔离。
沿用CONTENT_BOOKMARK_*环境变量，无新增配置、依赖或迁移，V1～V27不改写。

详情、首页和作者列表沿用ContentBookmarkReader，取消后本人viewerHasBookmarked变false，
其他用户不受影响。游客固定false。[GET本人收藏列表](content-bookmark-list.md)已接通，取消后的新请求不再返回该作品。

ContentBookmarkFlowIT覆盖不可见/不存在目标、本人范围、权限复核、重复与并发、PUT/DELETE
双向竞争、实际删除回滚、重新收藏时间/ID、共享配额及列表回显。
ContentBookmarkServiceTest验证输入与依赖错误隔离，错误快照增加外部契约已有unbookmarkContent定义。
