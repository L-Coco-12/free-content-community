# 取消点赞

[点赞与统计](content-like.md) · [结构](../STRUCTURE.md)

`DELETE /api/v1/contents/{contentId}/likes/me`需要Bearer身份，无请求体、查询参数、
expectedVersion或必需Idempotency-Key。成功204空响应，附X-Request-Id与no-store。

## 目标状态与范围

仅删除当前登录用户与目标作品的关系。关系不存在、重复取消、目标ID有效但作品不存在均返回204，
不借此返回内容详情或泄露目标是否存在。撤回、下架、移入回收站、公开修订损坏或素材失效，
都不妨碍清理本人的已有关系。不调用公开可见性校验器，不签名或访问MinIO。

身份与写入资格复用点赞：事务内复核认证版本、ACTIVE和当前邮箱验证状态；不以canCreate、
作者归属或审核角色作为门槛。缺失/无效身份401，受限账号403，ID格式422，额外输入400。
本接口契约没有404或409，不把重复取消解释为错误。

## 事务与并发

代码复用content.interaction的Controller、Service、事务组件及Mapper：

1. 事务外执行共享限流。
2. 账号共享锁复核资格。
3. 仅锁定内容主行ID，不判断公开状态。与PUT保持账号→内容→关系→统计的锁序，
   也避免和内容物理清理形成反向锁序；目标不存在时，外键保证不会遗留有效关系。
4. 按(user_id,content_id)删除，仅实际删除1行时原子扣减统计。
5. 统计更新带like_count>0条件；缺失统计行或异常0不夹成0假装成功，而是503并回滚关系删除。

关系删除和计数扣减同事务。重复请求不更新统计时间，作品编辑version、updated_at、
公开指针和审核状态均不改变。统计为0的行保留，可供以后重新点赞使用。

唯一关系记录锁决定同一用户点赞/取消的实际执行顺序。未提交点赞会阻塞随后取消，
未提交取消会阻塞随后重新点赞；最终计数与关系保持一致。不承诺乱序到达的两个请求按点击时间执行。

## 配额与回显

PUT和DELETE共享app.content.like及{content-like}键：来源120次/分钟、用户60次/分钟，
重复204也计数。两种操作合计受限，不通过交替调用绕过额度。环境变量和Lua脚本沿用点赞，
无新配置、依赖或迁移，既有V1～V26不改写。

真实删除后，详情、首页和作者列表沿用ContentLikeReader，likeCount减少、本人viewerHasLiked
变false；其他用户关系与标记不受影响。前端已有DELETE调用现在可以和PUT组成点赞切换。

ContentLikeFlowIT覆盖本人范围、隐藏/不存在目标、重复取消、共享配额、同人/多人并发、
PUT/DELETE双向竞争、计数故障及异常统计回滚、权限复核和列表回显。
ContentLikeServiceTest验证输入、限流和依赖错误隔离；CommonErrorDocumentationIT核对unlikeContent完整契约。
