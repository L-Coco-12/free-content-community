# 本人有效登录会话

`GET /api/v1/me/sessions` 需要 Bearer 身份，没有查询参数和请求体。成功200：

```json
{
  "data": {
    "items": [{
      "id": "session_id",
      "createdAt": "2026-11-16T08:00:00Z",
      "expiresAt": "2026-11-17T08:00:00Z",
      "lastSeenAt": "2026-11-16T09:00:00Z",
      "current": true,
      "deviceLabel": "我的电脑"
    }]
  },
  "requestId": "req_example",
  "serverTime": "2026-11-16T09:00:00Z"
}
```

按 `(createdAt,id)` 倒序，不分页。只返回本人索引中、与账号当前 authVersion 相同、尚未过期的
有效会话；缺失记录、已撤销及旧认证版本记录不进入结果。current 由认证上下文中的会话ID确定，
不接受客户端指定本人或当前会话。展示字段仅上述六项，不返回Token、Token摘要、账号认证版本
或Redis键。deviceLabel来源于登录时保存的设备名称，去除首尾空白；未填写时返回空字符串，
不推测设备型号或采集新增设备信息。时间使用UTC。

## 调用、授权与一致性

MySessionsController → MySessionsService接口/impl → MySessionsTransactionService →
auth.session.RedisSessionStore#listActive → session-list.lua → SessionMetadata → SessionListView。
账号业务和DTO归user，会话键及原子读取保留在auth.session；不跨模块调用Controller。

Service校验身份并取得独立配额。事务组件以账号共享锁复核账号存在、认证版本、禁用状态与邮箱。
WRITE_BLOCKED仍可读，不以canCreate或创作/后台角色作为门槛。账号锁保持到列表读取结束，
使密码变更或账号禁用的提交与读取有明确先后顺序，不跨MySQL和Redis承诺分布式事务。

Lua使用现有 `{sessions}` 槽和本人用户索引，在一个原子读取中复核当前凭证摘要对应的会话身份、
索引成员、认证版本和过期时间，再读取各会话显示元数据。若当前会话在认证后、原子读取前被退出、
淘汰、移除索引、过期或身份改变，返回401。并发登录、退出在Lua执行前或后生效，不会读出半次索引更新。
认证版本按十进制字符串相等比较，避免BIGINT转Lua浮点数产生精度丢失。

不执行全库KEYS/SCAN。读取数量受会话创建配置现有硬上限100约束，索引异常超过100项返回503，
不会静默截断。跨用户索引、损坏记录、重复会话ID、缺字段、非法时间、错误类型及无法确认的读取
结果返回503，不返回部分成功或泄露其他用户信息。Java再次检查投影类型、字段范围及当前会话存在。

session-list.lua不新增、删除或修改会话及索引，也不续期。现有Bearer认证会更新本次当前会话的
lastSeenAt，其他设备的lastSeenAt和所有会话的expiresAt/TTL保持原规则。

## 配额、错误与验证

独立 `app.user.session-query` / `{user-session-query}` 配额：来源120次/分钟、用户60次/分钟。
环境变量为USER_SESSION_QUERY_IP_PER_MINUTE_LIMIT、USER_SESSION_QUERY_USER_PER_MINUTE_LIMIT、
USER_SESSION_QUERY_REDIS_KEY_PREFIX。前缀仅用于限流；会话存储仍使用既有LOGIN_REDIS_KEY_PREFIX，
不会迁移或更换登录凭证。与公开个人资料、内容浏览及其他业务限流隔离，重复查询计数。
额外参数和请求正文在计数前400；获准后的查询失败不退还配额。

401 AUTHENTICATION_REQUIRED；403 PERMISSION_DENIED / ACCOUNT_RESTRICTED；400 MALFORMED_REQUEST；
429 RATE_LIMITED附正整数Retry-After；数据库、事务及Redis故障503 DEPENDENCY_UNAVAILABLE。
成功和失败带X-Request-Id、Cache-Control: no-store。错误文档完整保留契约的422和500通用错误。
无新增依赖或数据库迁移，不调整原有创建、认证、退出和改密的会话脚本。
[撤销本人指定会话](user-session-revocation.md)已接通，使用独立配额，允许撤销当前会话。

MySessionsFlowIT验证真实HTTP/MySQL/Redis本人范围、排序、活动时间、过期/旧版本过滤、并发撤销、
账号权限复核、配额、损坏数据和生成的OpenAPI字段；SessionMetadataReadTest验证Redis结果边界及
凭证保护；服务、配置和限流器单元测试验证错误隔离与启动参数。登录、退出和改密流程纳入回归。
