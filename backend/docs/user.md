# 账号与资料

[返回后端入口](../README.md) · [结构与边界](../STRUCTURE.md)

文中 `src/` 路径相对 `backend/`；验证数字为对应开发阶段的历史记录，当前结果以最新执行为准。

## 接口行为与约定

### 查询后台脱敏用户列表

`GET /api/v1/admin/users` 对应 `listAdminUsers`，仅邮箱已验证且具备 `user:read` 的管理员可用。
支持精确 `userId` 和账号 `status`，两者同时传入时取交集。默认第 1 页、每页 20 条，最多 100 条。
列表按注册时间和用户 ID 倒序，`total` 为筛选命中的总数，越界页返回空 `items`。

事务内复核管理员权限，并从同一快照完成计数、分页、批量角色读取。SQL 只投影页面需要的字段，
将邮箱显示为 `***@域名`，不合法的历史邮箱显示为 `***`。
后台用户查询使用 `app.user.admin-query`，默认每来源 120 次/分钟、每管理员 60 次/分钟。

### 读取后台用户详情

`GET /api/v1/admin/users/{userId}` 对应 `getAdminUser`。仅接受路径中的用户 ID，成功返回单个
`AdminUser`，沿用列表的权限复核、邮箱脱敏和查询配额。账号信息和角色在同一个只读事务快照中读取。
被查询账号处于限制写入或禁用状态时，管理员仍可查看。ID 格式不合法返回 422，用户不存在返回 404。

### 设置用户限制状态

`PUT /api/v1/admin/users/{userId}/restriction` 对应 `restrictUser`。具备 `user:restrict` 的已验证
ACTIVE 管理员提交 `expectedVersion`、`status`、`reason`。成功返回脱敏的 `AdminUser`。
状态实际变化时递增业务版本并写入审计。当前版本下重复设置相同状态返回当前结果。过期版本返回
`409 RESOURCE_VERSION_CONFLICT` 并携带 `currentVersion`。

WRITE_BLOCKED 允许读取和账号安全操作，并拦截业务写入。DISABLED 拒绝登录，数据库认证版本在
提交时递增，使旧会话立即失效。提交后按认证版本清理 Redis，清理失败时旧凭证仍然无法认证。
ACTIVE 解除账号限制，之后可重新登录。作品仍按各自的发布状态展示。

有效管理员必须已验证邮箱、拥有 ADMIN 角色且处于 ACTIVE。限制最后一位有效管理员返回
`409 LAST_ADMIN_PROTECTED`。状态限制与替换角色共用 V28 的数据库锁行，使两类写事务串行执行。
账号状态、业务版本、认证版本与审计在同一事务提交。

独立限流配置为 `app.user.restriction`，默认每来源 120 次/分钟、每管理员 60 次/分钟。

### 替换用户角色集合

`PUT /api/v1/admin/users/{userId}/roles` 对应 `replaceUserRoles`。具备 `role:manage` 的已验证
ACTIVE 管理员提交 `expectedVersion`、完整的 `roles`、`reason`。角色集合必须包含 USER，
可添加 MODERATOR 或 ADMIN，角色不能重复。成功返回脱敏的 `AdminUser`。

同版本且集合相同返回当前结果，即使顺序不同。角色实际变化时，将差异角色、业务版本、认证版本
和 V29 审计记录在同一事务中提交。旧凭证立即失效，提交后按认证版本清理 Redis 中的旧会话。
移除最后一位有效管理员的 ADMIN 角色返回 `409 LAST_ADMIN_PROTECTED`。过期版本返回
`409 RESOURCE_VERSION_CONFLICT` 并携带 `currentVersion`。

独立限流配置为 `app.user.role-update`，默认每来源 120 次/分钟、每管理员 60 次/分钟。

### 查询预置角色及权限

`GET /api/v1/admin/roles` 对应 `listRoles`。邮箱已验证的管理员可读取 `data.items`，列表按
USER、MODERATOR、ADMIN 排列，每项包含角色代码、名称和契约定义的权限标识。请求无参数、无正文。
服务在只读事务中复核账号、认证版本和管理员角色。目录来自预置角色定义，创作资格等条件由相应接口校验。

角色查询使用独立的 `app.user.role-query` 配额，默认来源每分钟 120 次、管理员每分钟 60 次。
环境变量为 `USER_ROLE_QUERY_IP_PER_MINUTE_LIMIT`、`USER_ROLE_QUERY_USER_PER_MINUTE_LIMIT`
和 `USER_ROLE_QUERY_REDIS_KEY_PREFIX`。超限返回 429，响应带 `Retry-After`。

### 查看公开个人主页

`GET /api/v1/users/{userId}` 对应 `getPublicUser`，游客可用；携带 Authorization 时必须验证，
无法使用的凭证返回 401 AUTHENTICATION_REQUIRED，不降级游客，认证依赖故障返回 503。
被查看账号的 ACTIVE、WRITE_BLOCKED、DISABLED 状态不隐藏其公开资料；不存在的用户返回 404。

成功返回 ApiResponse<PublicUser>，仅包含 id、displayName、bio、avatarUrl、createdAt、
publishedContentCount、followerCount 七个公开字段，不读取或返回邮箱、密码、角色、账号权限、
会话或版本。READY头像绑定及[稳定读取地址](user-avatar-access.md)已实现，无有效头像返回avatarUrl=null。
资料查询不签名或访问MinIO，图片请求单独检查并302跳转。followerCount 来自已提交的关注关系。

路径 ID 只允许 1～64 位字母、数字、下划线和短横线，非法返回 422，violations 定位 path/userId。
不接受任何查询参数，未定义及重复参数返回 400 MALFORMED_REQUEST。

调用顺序：

1. [PublicUserController#getPublicUser](../src/main/java/com/freecontent/community/user/controller/PublicUserController.java)：拒绝查询参数并包装响应。
2. [PublicUserServiceImpl#getPublicUser](../src/main/java/com/freecontent/community/user/service/impl/PublicUserServiceImpl.java)：校验 ID、取得配额、调用只读事务；数据库及事务故障转换为 503。
3. [PublicUserTransactionService#read](../src/main/java/com/freecontent/community/user/service/transaction/PublicUserTransactionService.java)：以 readOnly + REPEATABLE_READ 读取资料和公开内容数。
4. [PublicUserReader#read](../src/main/java/com/freecontent/community/user/service/read/PublicUserReader.java)：MANDATORY 加入调用方事务，Mapper 只选取公开资料字段。
5. [PublicContentCountReader#countByAuthor](../src/main/java/com/freecontent/community/content/service/read/PublicContentCountReader.java)：MANDATORY 加入同一事务，复用内容模块 PublicContentMapper.countVisibleByAuthor 的 VISIBLE 条件，不复制统计 SQL。

正常请求执行两次业务 SQL：公开资料、公开可见内容数；用户不存在时只执行第一次。
认证查询不包含在两次中。只统计当前公开发布修订及有效素材，不统计工作稿；异常发布元数据从数量中排除。
查询不修改账号、内容、修订、业务审计或 Outbox。作者内容列表见[指定作者的公开作品](content-author-list.md)。

限流配置为 app.user.public-query，独立于内容浏览和资料修改。默认来源 120 次/分钟，
登录访问者额外 60 次/分钟；游客和登录请求共用 `{user-public-query}` 来源窗口，用户窗口绑定访问者而非目标作者。
参数或凭证非法不计配额；合法查询（包括重复读取和用户不存在）计数，429 携带正整数 Retry-After。
Redis 故障返回 503，不当作成功或游客请求。

### 关注用户

`PUT /api/v1/me/following/{userId}` 对应 `followUser`，使用 Bearer 会话，仅接收路径参数。发起人须为
已验证邮箱的 ACTIVE 账号。ACTIVE 和 WRITE_BLOCKED 账号可以被关注，目标禁用或不存在均返回
`404 RESOURCE_NOT_FOUND`。关注本人或 ID 格式非法返回 `422 VALIDATION_ERROR`。

首次关注与重复关注均返回 204。V30 在 `user_follow` 上约束关注人和被关注人这一对唯一。首次插入时
在同一 MySQL 事务为目标保存未读 `NEW_FOLLOWER` 通知，写入失败会回滚关系。通知文本包含发起人的公开昵称，
`targetType` 和 `targetId` 均为空。公开主页和作品作者资料通过目标索引读取同一份关注关系计数。

关注和取消关注共用 `app.user.follow` 配额，默认每用户 60 次/分钟、每来源 120 次/分钟。有效格式的已认证请求
计入配额，包括重复请求。超限返回 429 并带 `Retry-After`，Redis 或数据库故障返回 503。

### 取消本人关注

`DELETE /api/v1/me/following/{userId}` 对应 `unfollowUser`，使用 Bearer 会话，仅接收路径参数。
服务在事务内复核发起人的认证版本、邮箱验证和 ACTIVE 状态，按发起人及目标 ID 删除关注关系。
关系已删除或从未建立时均返回 204，目标账号禁用时仍可清理已有关系。已发送的关注通知会保留。

关注关系删除后，公开资料和作品详情中的粉丝数随数据更新。以后重新关注会创建一条新的关系和通知。

### 查询本人关注列表

`GET /api/v1/me/following` 对应 `listMyFollowing`，使用 Bearer 会话。邮箱已验证的 ACTIVE 或 WRITE_BLOCKED 账号可查看
本人关注列表。每项使用 `PublicUser` 公开资料及实时粉丝数、公开作品数。公开资料可见性与个人主页一致，
因此已有关注关系中的禁用用户仍可列出。

首次请求可传 `limit`，默认 20，范围 1～50。后续用 `nextCursor` 作为 `cursor` 并保持 `limit` 一致。响应包含
`items`、`hasMore`、`nextCursor` 和 `snapshotAt`。列表按关注关系的 `created_at` 和 `id` 倒序，条目的 `createdAt`
为被关注用户注册时间。V31 增加对应的覆盖索引，资料和公开作品数在同一只读事务中批量读取。

`snapshotAt` 固定本轮关注时间上界。分页中发生的取消关注会影响后续页，晚于该上界的新关注在开启新一轮查询时可见。
游标绑定接口、当前账号、每批数量和上界，默认从首批开始有效 15 分钟。错用或篡改返回 `400 CURSOR_INVALID`，
过期返回 `410 CURSOR_EXPIRED`。列表使用独立的 `app.user.follow-query` 配额，默认每用户 60 次/分钟、每来源 120 次/分钟，
返回 `429` 时含 `Retry-After`。

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| ip-per-minute-limit | USER_PUBLIC_QUERY_IP_PER_MINUTE_LIMIT | 120 |
| user-per-minute-limit | USER_PUBLIC_QUERY_USER_PER_MINUTE_LIMIT | 60 |
| redis-key-prefix | USER_PUBLIC_QUERY_REDIS_KEY_PREFIX | fc:user: |

配额必须为正整数，Redis 前缀不能为空或包含 `{`、`}`；非法配置阻止启动。

### 修改当前账号密码

`PUT /api/v1/me/password`需要有效Bearer身份，JSON请求仅包含currentPassword和newPassword。
目标用户由认证上下文确定，不接收客户端传入的userId或邮箱。两次新密码确认由前端比较，
不增加confirmPassword字段；未知字段按现有规范拒绝。成功204且无响应体，不签发新Token，前端清理身份并引导重新登录。

当前密码支持1～128个Unicode字符，新密码支持12～128个字符，原样参与Argon2id计算。
旧密码使用PasswordHasher.matches校验，新密码使用encode生成带盐哈希，两者共享既有计算并发限制。
旧密码错误返回401 AUTHENTICATION_FAILED，保留原密码与会话；前端应在表单提示，不作为会话失效处理。
无效或过时身份返回401 AUTHENTICATION_REQUIRED；WRITE_BLOCKED允许账号安全操作，DISABLED拒绝访问。

认证上下文携带认证时的authVersion。写事务锁定账号，再核对该版本、已验证的旧密码哈希和账号状态，
随后原子更新password_hash与auth_version。若校验与写入之间发生了其他改密或找回，请求被拒绝，不覆盖新凭证。
账号资料version、角色、邮箱验证状态与账号限制保持不变。成功后包括当前会话在内的全部旧Token失效。
提交后复用旧版本会话清理；新登录会话保留，Redis清理失败记录日志仍返回204，旧Token仍因版本不匹配被拒绝。

app.user.password-change配置为同来源每分钟30次、同用户每小时20次，采用独立{password-change}配额键。
环境变量为PASSWORD_CHANGE_IP_PER_MINUTE_LIMIT、PASSWORD_CHANGE_USER_HOURLY_LIMIT和PASSWORD_CHANGE_REDIS_KEY_PREFIX。
所有获准尝试均计数，包括错误旧密码，超限429附Retry-After；不会因此锁定账号。
来源取TCP对端地址，仅当对端属于可信代理网段时采用X-Forwarded-For。不新增表或迁移，复用现有账号与认证版本字段。

当前账号改密的DTO、Service接口及实现、事务、限流、配置均在user模块，测试同样归user。
密码哈希、认证上下文及会话存储继续复用auth的既有安全能力；不因技术复用改变用户业务归属。
本次结构迁移保留接口及PASSWORD_CHANGE_*环境变量，Redis键名和计数语义保持不变。

2026-11-06验证：全部153项测试通过，覆盖真实HTTP改密、旧密码错误时保留会话、全会话失效、
新密码登录、状态限制、并发凭证变更保护、数据库写入失败、清理失败、新登录保留与独立限流；
注册、登录、退出和验证码重置流程回归通过。

### 获取当前用户资料

`GET /api/v1/me`需要有效的`Authorization: Bearer <accessToken>`，无请求体，不使用客户端用户ID作为查询目标。
成功200，统一包装为data、requestId、serverTime；data为已有CurrentUser契约，包含账号资料、当前角色、账号状态及canCreate。
avatarAssetId为空时仍返回null。密码哈希、Token、Token摘要、sessionId和authVersion不进入响应。

认证链先校验Redis会话，再读取MySQL当前账号和角色并核对认证版本与状态，构建本次请求的SessionPrincipal。
MeController调用user模块的CurrentUserService，直接复用其中的CurrentUser，避免在同一次请求中再次查询数据库。
后续请求会重新认证，资料和角色变动不会长期停留在登录时的快照。

WRITE_BLOCKED允许查询，返回canCreate=false；DISABLED返回403 ACCOUNT_RESTRICTED。
缺少、无效、过期、已撤销Token或认证版本过时返回401 AUTHENTICATION_REQUIRED；Redis/MySQL不可用返回503。
响应使用Cache-Control: no-store，读取不修改资料version、auth_version，也不续期会话。

2026-11-06验证：全部162项测试通过，新增真实HTTP当前用户查询、账号隔离、资料/角色更新、
账号限制、过期/退出凭证拒绝、响应字段白名单及会话不续期验证；注册、登录、退出和两种改密流程回归通过。

### 本人有效登录会话

`GET /api/v1/me/sessions`已接通，按创建时间和ID倒序返回本人有效会话的六个显示字段，
不返回凭证，不延长有效期。独立限流app.user.session-query，详细语义见[本人会话列表](user-sessions.md)。
`DELETE /api/v1/me/sessions/{sessionId}`撤销本人指定会话，成功及目标不存在均204，
独立限流app.user.session-revocation；详见[指定会话撤销](user-session-revocation.md)。

### 修改当前用户资料

`PATCH /api/v1/me`需要Bearer身份，提交GET /me返回的资料version作为expectedVersion，
并至少提供displayName、bio、avatarAssetId中的一个字段。只更新提供的字段，成功200并返回写入后的CurrentUser及新version。
不接收客户端指定的userId或邮箱，不允许回传roles、accountStatus、emailVerified、密码等字段；未知字段返回400。

| 字段 | 省略 | 显式null | 其他输入 |
| --- | --- | --- | --- |
| displayName | 保持原值 | 422 | 非空昵称，1～40个Unicode字符 |
| bio | 保持原值 | 422 | 最多500个Unicode字符，空字符串清空简介 |
| avatarAssetId | 保持原值 | 清空头像 | 绑定本人READY的AVATAR素材 |

DTO通过JSON Setter保留字段存在标记，拒绝客户端篡改内部标记，不将数字、数组或对象静默转换为资料字符串。
昵称和简介使用Unicode码点计数，不静默截断或trim。只提交expectedVersion或不符合字段校验的输入返回422。
expectedVersion必须为1～2147483647的JSON整数；整数值越界返回422及body/expectedVersion提示，字符串、小数等类型错误仍返回400。

UserProfileService与事务、DTO、配置、限流和校验都位于user模块。
事务锁定目标账号并再次检查认证版本、ACTIVE状态及资料版本；提供非空头像时，按账号→目标素材顺序持有共享素材锁并校验归属、AVATAR用途与READY状态，然后通过MyBatis if/set生成动态更新，
SQL列由后端白名单固定，值使用参数绑定；WHERE包含用户ID、expectedVersion、认证版本及ACTIVE状态。
版本冲突返回409 RESOURCE_VERSION_CONFLICT及currentVersion，客户端重新读取并确认后再保存，不自动覆盖。
对本期已支持且通过校验的字段，值均相同时返回200和当前资料，version与updated_at不变；过时版本仍返回409。

实际修改只递增资料version，不更改auth_version、密码、角色、邮箱验证状态和会话，不要求重新登录。
接口返回数据库更新后的资料，而非认证阶段的旧CurrentUser快照。WRITE_BLOCKED或DISABLED不能修改资料。
认证状态在过滤器验证后发生变化时，事务复核会拒绝写入；依赖故障返回503，响应仍遵循Problem Details及no-store规范。

头像绑定已接通[图片自动校验通路](media-processing.md)。客户端上传AVATAR图片，完成上传并等待任务SUCCEEDED、素材READY后，
提交最新expectedVersion和avatarAssetId即可保存。素材不存在或不属于本人统一404 RESOURCE_NOT_FOUND；
本人素材用途不匹配返回422 VALIDATION_ERROR及body/avatarAssetId提示；尚未READY（含FAILED）返回409 MEDIA_NOT_READY。
校验失败时昵称、简介、头像及版本均保持原值，不部分保存。同值头像仍校验目标素材，合法同值不递增版本；旧版本优先返回版本冲突。
avatarAssetId:null清空，省略保持原值；清空或替换不会删除旧素材，也不会改变素材状态，仍可复用本人READY头像。
保存只绑定已校验素材ID，不重新下载或HEAD，不签发地址，不扩展CurrentUser响应；公开avatarUrl展示见[头像读取](user-avatar-access.md)。

app.user.profile-update配置：同来源每分钟30次、同用户每小时20次，独立{profile-update}配额键。
环境变量为PROFILE_UPDATE_IP_PER_MINUTE_LIMIT、PROFILE_UPDATE_USER_HOURLY_LIMIT和PROFILE_UPDATE_REDIS_KEY_PREFIX，
默认Redis前缀fc:user:。所有进入业务的有效请求均计数，包含相同值保存及版本冲突；超限429附Retry-After。
来源使用TCP对端地址，仅当对端属于可信代理网段时采用X-Forwarded-For。

2026-11-06验证：全部183项测试通过，覆盖真实HTTP/MySQL/Redis的部分更新、空值语义、Unicode边界、
SQL参数绑定、相同值不增版本、并发版本冲突、事务内身份/状态复核、头像请求不部分保存、提交限流及API文档字段约束。

## 调用链阅读顺序

### 修改当前账号密码怎样阅读

1. [MeController](../src/main/java/com/freecontent/community/user/controller/MeController.java)：
   `PUT /api/v1/me/password`，从认证上下文取得目标账号，请求只接收currentPassword和newPassword，成功204无响应体。
2. [PasswordChangeService](../src/main/java/com/freecontent/community/user/service/PasswordChangeService.java)声明接口；
   [PasswordChangeServiceImpl](../src/main/java/com/freecontent/community/user/service/impl/PasswordChangeServiceImpl.java)
   按已认证用户及来源独立限流，在事务外校验旧密码并计算新密码哈希。
3. [PasswordChangeTransactionService](../src/main/java/com/freecontent/community/user/service/transaction/PasswordChangeTransactionService.java)：
   锁定账号，复核认证版本、密码哈希和账号状态，再通过条件更新原子修改密码和auth_version。
4. 事务提交后复用RedisSessionStore.revokeBeforeVersion撤销旧会话；保留提交后新登录的会话。

SessionPrincipal额外携带认证时的authVersion，防止校验与写入之间发生凭证变更后被旧请求覆盖。
旧密码错误返回401 AUTHENTICATION_FAILED，保持原密码和会话有效；认证版本过时返回401 AUTHENTICATION_REQUIRED。
WRITE_BLOCKED允许改密，DISABLED拒绝访问。成功后全部旧会话失效，包括当前会话；Redis清理失败仍以认证版本保证逻辑失效。
两次新密码确认属于前端交互，不向本接口增加confirmPassword或客户端指定的userId。

改密配置为app.user.password-change，原有PASSWORD_CHANGE_*环境变量和Redis配额键格式保持不变。
对应单元和集成测试位于src/test/java/com/freecontent/community/user/下，与业务模块对应。

### 获取当前用户资料怎样阅读

1. [MeController](../src/main/java/com/freecontent/community/user/controller/MeController.java)的getCurrentUser方法：
   `GET /api/v1/me`需要Bearer身份，无请求体和用户ID参数，返回200及ApiResponse<CurrentUser>。
2. [CurrentUserService](../src/main/java/com/freecontent/community/user/service/CurrentUserService.java)声明用户业务接口；
   [CurrentUserServiceImpl](../src/main/java/com/freecontent/community/user/service/impl/CurrentUserServiceImpl.java)
   复用认证上下文中本次请求的CurrentUser，不重复查询数据库。
3. [SessionAuthenticationService](../src/main/java/com/freecontent/community/auth/service/SessionAuthenticationService.java)
   在进入Controller前查询MySQL账号及角色，校验认证版本和状态后构建SessionPrincipal。

返回CurrentUser的契约字段，不直接序列化UserAccount或SessionPrincipal，避免暴露密码哈希、Token摘要和authVersion。
WRITE_BLOCKED可以查询，canCreate=false；DISABLED返回403，无效或已撤销Token返回401，依赖故障返回503。
后续请求重新认证并读取当前资料、角色与状态。查询不修改账号版本，也不延长会话有效期，响应使用no-store。

### 修改当前用户资料怎样阅读

1. [MeController](../src/main/java/com/freecontent/community/user/controller/MeController.java)的updateProfile方法：
   `PATCH /api/v1/me`需要Bearer身份，成功200并返回更新后的CurrentUser。
2. [UpdateProfileRequest](../src/main/java/com/freecontent/community/user/dto/UpdateProfileRequest.java)：
   以JSON Setter记录字段是否出现，包括显式null；仅接收expectedVersion、displayName、bio、avatarAssetId。
   [ProfileUpdateValidator](../src/main/java/com/freecontent/community/user/validation/ProfileUpdateValidator.java)
   拒绝空修改、空昵称及bio:null；Unicode长度使用共用码点校验。
3. [UserProfileService](../src/main/java/com/freecontent/community/user/service/UserProfileService.java)及
   [UserProfileServiceImpl](../src/main/java/com/freecontent/community/user/service/impl/UserProfileServiceImpl.java)：
   从认证上下文确定目标账号，并执行独立提交限流。
4. [ProfileUpdateTransactionService](../src/main/java/com/freecontent/community/user/service/transaction/ProfileUpdateTransactionService.java)：
   锁定账号，复核认证版本、ACTIVE状态及expectedVersion；非空头像通过MediaAssetMapper.findOwnedForShare在同一事务中校验本人、用途及READY。
   修改后重新读取账号和角色，不能返回写入前的上下文快照。
5. [UserAccountMapper](../src/main/java/com/freecontent/community/user/mapper/UserAccountMapper.java)的updateProfileFields方法：
   以if/set动态生成字段白名单内的SET，值使用参数绑定；WHERE同时限定用户、资料版本、认证版本及ACTIVE状态。

支持昵称、简介、绑定和清空头像；省略字段保持原值，bio空字符串表示清空简介，avatarAssetId:null表示清空头像。
非空头像要求本人READY的AVATAR素材，整个请求不发生部分保存。ProfileUpdateFlowIT覆盖真实上传、自动校验及头像保存完整链路。
支持字段通过校验且值未变化时，返回200且不修改version或updated_at；版本过时始终409 RESOURCE_VERSION_CONFLICT，并返回currentVersion。
实际修改递增资料version，不修改authVersion、密码或会话有效期。配置见app.user.profile-update，默认来源30次/分钟、用户20次/小时。
