# 撤销本人指定会话

`DELETE /api/v1/me/sessions/{sessionId}` 需要Bearer身份，无查询参数、请求体、expectedVersion或
必需Idempotency-Key。sessionId允许1～64位ASCII字母、数字、下划线和短横线。成功204空响应，
带X-Request-Id、Cache-Control: no-store。

仅在当前账号的会话索引中查找目标。本人有效、过期或旧版本会话均可撤销；目标不存在、已撤销、
未找到及属于他人时统一204，不查询他人索引、不删除他人凭证，也不暴露目标是否存在。
不存在目标的重复204请求也计数。删除后，目标Token不能再认证，GET本人会话列表不再返回该会话。
重新登录生成新的会话ID，重复撤销旧ID不会影响新会话。

## 当前会话与权限

可以撤销当前会话，首次204后，该Token后续请求返回401。重复撤销其他已失效会话时，调用者
仍须持有有效身份；不能把已经失效的Token作为幂等重放凭证。

撤销其他会话：在实际删除前，Redis再次检查当前会话的身份、认证版本、索引成员及失效时间。
如果它在过滤器认证之后、Lua执行之前被撤销、淘汰、移除索引、过期或身份发生变化，返回401，
目标保持原样。两个会话同时撤销对方且均已通过认证时，先执行者成功204，另一个凭证已失效，返回401。

撤销自己：沿用已有退出接口的并发语义，已通过认证但当前会话又被并发退出或过期时，仍204。
若凭证键仍存在但身份/认证版本与上下文不符，返回401，不删除变化后的记录。
同一当前会话的两个请求都先通过认证后并发撤销，可均返回204。

事务内共享锁先复核账号存在、authVersion、禁用及邮箱状态。WRITE_BLOCKED仍可执行会话撤销，
与已有安全过滤器的此路由例外一致；不要求canCreate、USER创作角色或后台角色。
禁用403 ACCOUNT_RESTRICTED，邮箱未验证403 PERMISSION_DENIED，认证版本变化401。
此账号复核适用于撤销自己和其他会话。

## 实现与原子删除

user.SessionRevocationController → SessionRevocationService接口/impl →
SessionRevocationTransactionService → auth.session.RedisSessionStore#revokeOwned → session-revoke-owned.lua。
控制器、服务、事务、限流、配置和测试归user；会话键与原子存储能力归auth.session。

MySQL只读事务保持账号共享锁直到Redis执行结束，防止账号禁用或改密提交与本次授权交叉。
readOnly仅约束MySQL读取，不阻止Redis撤销；不承诺跨MySQL与Redis的事务回滚。
Redis或提交结果未知时返回503，目标可能已撤销，后续可用有效身份重新查询或重复撤销。

所有会话键使用现有LOGIN_REDIS_KEY_PREFIX及 `{sessions}` 槽。撤销其他会话时，通过本人ZSET
扫描最多100个现有会话索引项，按会话ID匹配，不使用全库KEYS/SCAN或新增索引。
脚本先检查索引类型、摘要格式和每个存在记录的owner/sessionId，验证无重复ID后，再删除唯一
目标凭证键及索引成员。Lua运行时错误不自动回滚，所以不得边扫描边删除。
跨用户引用、损坏身份、错误类型、重复ID或索引异常膨胀均503，目标不发生部分删除。
缺失凭证键可跳过，未知目标不清理无关索引项；后续创建会话沿用原有过期清理规则。

撤销自己直接按已认证摘要检查并删除，不要求其他设备的展示元数据正常。
撤销目标不依赖设备名称、活动时间等显示字段，不调用列表投影，不延长任何会话有效期，
不改变账号版本、密码或其他设备的活动时间。现有认证仍只更新本次调用者的lastSeenAt。
原有创建、认证、退出、会话列表和旧版本清理Lua脚本保持原样。

## 独立配额与验证

app.user.session-revocation / `{user-session-revocation}`：来源120次/分钟、用户60次/分钟，
与会话查询和其他业务配额隔离。环境变量为USER_SESSION_REVOCATION_IP_PER_MINUTE_LIMIT、
USER_SESSION_REVOCATION_USER_PER_MINUTE_LIMIT、USER_SESSION_REVOCATION_REDIS_KEY_PREFIX。
额外输入400、ID格式错误422，均在计数前拒绝。合法重复204计数；获准后的依赖失败不退还。
429带正整数Retry-After，数据、事务或Redis故障503，不把结果未知伪装成204。

SessionRevocationFlowIT使用真实HTTP/MySQL/Redis覆盖归属、幂等、自撤销、凭证失效、权限复核、
损坏索引不部分删除、独立限流及并发。OwnedSessionRevocationTest验证不确定Redis结果和身份边界，
服务/配置/限流器测试覆盖错误隔离和启动校验。会话列表、登录退出、修改及找回密码纳入回归。
无新增依赖或数据库迁移，V1～V27及权威OpenAPI不改写。
