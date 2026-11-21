# 开发与验证

## 本人通知列表

`GET /api/v1/me/notifications` 按登录身份读取 `user_notification`。支持
`readState=ALL|UNREAD|READ`，`limit` 默认 20、最大 50。结果按 `createdAt` 和 `id` 倒序；
只展示通知中已保存的说明，关联目标不可见时仍可查看该通知。

游标使用固定的创建时间上界，绑定当前账号、筛选和每页条数。后续读取按通知的当前已读状态筛选。
游标有效期默认 15 分钟，过期返回 `410 CURSOR_EXPIRED`。可配置 `NOTIFICATION_CURSOR_KEY_BASE64`
作为独立密钥，省略时使用现有内容游标密钥，接口用途在加密时单独绑定。
独立配额 `app.notification.query` 默认每来源 120 次/分钟、每用户 60 次/分钟。

`GET /api/v1/me/notifications/unread-count` 用于未读角标，只按登录身份统计 `read_at IS NULL` 的通知。
成功时返回 `data.count` 和查询时间 `data.asOf`，没有未读通知时数量为 0。
未读数量与通知列表共用 `app.notification.query` 配额，查询保持通知的已读状态。

## 2026-11-13 运营强制下架验证

新增 `POST /api/v1/admin/contents/{contentId}/take-downs`，规则与调用链见[强制下架](content-take-down.md)。
按红→绿验证精确后台 POST 授权、历史发布稿门槛、ACTIVE/DELETED 保留、DRAFT/IN_REVIEW 取消、PENDING 失效、
TAKEN_DOWN 新键拒绝与成功旧键重放、严格原因分类/Unicode 长度、独立配额、资格变化、租约和过期接管。
真实 MySQL 验证内容、工作链、处置、审计、作者未读通知、公开/审核失效事件和成功快照同事务，任一写入点失败整体回滚。
新增 notification 模块最小事务写入器，MANDATORY 加入业务事务，原因仅截取通知摘要，完整原因保留；无通知列表/已读 API 或消息投递。
强制制造同键空记录竞争验证唯一键有界重读，验证多操作员争用同一版本、交叉处置对方作品及与审批竞争。
真实串联作者新建整改修订、提交 REMEDIATION、审批发布，确认原下架成功重放不再次下架新公开稿；删除恢复及重发旧稿不能解除标记。
V24 新增处置、幂等、审计、通知表并扩展公开失效事件类型，已在本机执行；V1～V23 保持原样，无新增依赖。
最终在源码哈希一致的 `.local-dev/structure-verification/content-take-down-final/backend` 执行
`mvn -B -ntp clean verify`：2780 项全部通过，其中 1248 项单元/Web、1532 项真实集成，零失败、零错误、零跳过，打包成功。
659 个源码/资源/pom 文件与工作区一致；原 44 个错误契约操作保留，新增后覆盖 45 个操作。
日志 `.local-dev/structure-content-take-down-final.log`，统计 `.local-dev/content-take-down-task/verification-summary.json`。
原始 OpenAPI、真实 infra/.env 和 IDE 设置未变；保留此前未提交改动，未提交、推送或启动独立开发后端。
本次未推进处置查询、举报处理、通知读取、媒体处理 worker 或 MQ 投递器/消费者。

## 2026-11-13 后台指定内容工作区验证

新增 `GET /api/v1/admin/contents/{contentId}`，规则与调用链见[后台内容详情](content-admin-detail.md)。
按红→绿验证精确 GET 授权、其他方法及未实现子路径拒绝、跨作者及本人范围、全部生命周期/发布状态、
路径和查询参数、当前后台资格复核、损坏修订绑定及依赖故障失败关闭，以及列表/详情/合法 404 合并计算配额。
真实 HTTP/MySQL 验证两个修订及有序引用保持各自指针、旧审核冻结稿与当前工作区区分、非 READY 素材及停用标签引用保留、无业务写入。
并发测试在详情读取内容行后调用真实草稿保存接口，验证作者提交新版本成功，详情仍返回同一旧快照的版本及正文。
首次暂停点也拦截了草稿保存自身读取，造成测试等待；将暂停限制为第一次详情读取后，保留真实写入和快照断言并通过。
最终在源码哈希一致的 `.local-dev/structure-verification/admin-content-detail-final/backend` 执行
`mvn -B -ntp clean verify`：2661 项全部通过，其中 1195 项单元/Web、1466 项真实集成，零失败、零错误、零跳过，打包成功。
637 个源码/资源/pom 文件与工作区一致；原 43 个错误契约操作保留，新增后覆盖 44 个操作。
日志 `.local-dev/structure-admin-content-detail-final.log`，统计 `.local-dev/admin-content-detail-task/verification-summary.json`。
复用现有后台内容查询组件和配额，无新增配置、依赖或迁移；V1～V23、原始 OpenAPI、真实 infra/.env、.env.example 和 IDE 设置未变。
保留此前未提交改动，未提交、推送或启动独立开发后端；未推进强制下架或其他接口。

## 2026-11-13 后台全部状态内容列表验证

新增 `GET /api/v1/admin/contents`，规则与调用链见[后台内容列表](content-admin-list.md)。
按红→绿验证精确后台 GET 授权、全平台及全部状态范围、作者/生命周期/发布状态 AND 筛选、更新时间与 ID 稳定排序、
默认与超大页码、空结果、严格查询参数、独立配额、当前资格重检和依赖故障失败关闭。
真实 HTTP/MySQL 验证同一只读快照内 COUNT、分页和修订装配的一致性，批量有序引用、损坏指针拒绝及无业务写入。
新增 content.moderation；WorkspacePage 移至 content.dto，共享模型字段及 Schema 保持原样，本人列表相关 6 个文件仅调整 import。
回归本人列表 32 项真实流程，并扩展模块边界检查防止运营查询依赖 workspace 内部实现。
最终在源码哈希一致的 `.local-dev/structure-verification/admin-content-final/backend` 执行
`mvn -B -ntp clean verify`：2611 项全部通过，其中 1175 项单元/Web、1436 项真实集成，零失败、零错误、零跳过，打包成功。
635 个源码/资源/pom 文件与工作区一致；原 42 个错误契约操作保留，新增后覆盖 43 个操作。
日志 `.local-dev/structure-admin-content-final.log`，统计 `.local-dev/admin-content-list-task/verification-summary.json`。
无新增依赖或迁移，V1～V23、原始 OpenAPI、真实 infra/.env 和 IDE 设置未变；保留此前未提交改动，未提交或推送。
本任务只实现后台集合读取，未推进后台内容详情、强制下架、其他接口或 worker，未启动独立开发后端。

## 2026-11-13 从回收站恢复作品验证

新增 `POST /api/v1/creator/contents/{contentId}/restorations`，规则与调用链见[恢复作品](content-restoration.md)。
按红→绿验证恢复状态、草稿和历史保留、下架标记、旧审核不复活、严格 VersionRequest 与幂等头、
24 小时成功快照重放、请求指纹、60 秒租约和接管、旧令牌隔离、独立配额、领取后的资格复核及原子回滚。
真实并发验证同键与不同键只恢复一次，以及旧 DELETE 不能误删新恢复版本；恢复后仍不公开，另行重新公开后旧恢复重放不会撤回新发布。
损坏幂等快照失败关闭；限流依赖及清理失败留下的租约可以在过期后接管恢复。
V23 新建恢复幂等记录和最小审计，生命周期 Outbox 增加 CONTENT_RESTORED 类型，已在本机执行；V1～V22 保持原样，无新增依赖。
最终在源码哈希一致的 `.local-dev/structure-verification/content-restore-final/backend` 执行
`mvn -B -ntp clean verify`：2541 项全部通过，其中 1136 项单元/Web、1405 项真实集成，零失败、零错误、零跳过，打包成功。
623 个源码/资源/pom 文件与工作区一致；原 41 个错误契约操作保留，新增后覆盖 42 个操作。
日志 `.local-dev/structure-content-restore-final.log`，统计 `.local-dev/content-restoration-task/verification-summary.json`。
原始 OpenAPI、真实 infra/.env 和 IDE 设置未变；保留此前未提交改动，未提交、推送或启动独立开发后端。
本任务未推进其他接口、媒体处理 worker、Outbox 投递器或消费者。

## 2026-11-13 作者移入回收站验证

新增 `DELETE /api/v1/creator/contents/{contentId}?expectedVersion=7`，规则与调用链见[移入回收站](content-deletion.md)。
按红→绿验证逻辑删除、四种发布状态保留、普通草稿及历史保留、PENDING 失效与 IN_REVIEW 终结、
已删除旧版本重复无副作用、参数和权限边界、独立配额、每个写入点失败的整体回滚，以及与审批、建草稿、重新公开并发。
真实 HTTP 验证删除后公开详情、全站/作者列表、作者公开数量和新的媒体取址均不再返回目标，
本人工作区及回收站仍可读取，素材元数据与历史引用保留。
V22 新建删除审计及可空历史发布指针的生命周期 Outbox，已在本机执行；有待审时另保存审核失效事件。
数据库真实验证存在失效审核时不能遗漏前后版本；V1～V21 保持原样，无新增依赖。
最终在源码哈希一致的 `.local-dev/structure-verification/content-delete-final/backend` 执行
`mvn -B -ntp clean verify`：2456 项全部通过，其中 1097 项单元/Web、1359 项真实集成，零失败、零错误、零跳过，打包成功。
606 个源码/资源/pom 文件与工作区一致；原 40 个错误契约操作保留，新增后覆盖 41 个操作。
日志 `.local-dev/structure-content-delete-final.log`，统计 `.local-dev/content-deletion-task/verification-summary.json`。
原始 OpenAPI、真实 infra/.env 和 IDE 设置未变；保留此前未提交改动，未提交、推送或启动独立开发后端。
本任务未实现恢复接口、媒体处理 worker、Outbox 投递器或消费者。

## 2026-11-13 重新公开最近批准稿验证

新增 `PUT /api/v1/creator/contents/{contentId}/publication`，规则与调用链见[重新公开](content-republication.md)。
按红→绿验证最近 APPROVED 选择、同版旧版本无副作用成功、当前公开条件、草稿和待审链保留、状态及权限保护、
独立请求配额、事务回滚、审计和 Outbox，以及与真实审批、创建草稿、撤回接口的并发。
重发更新 publishedAt，保留 firstPublishedAt；验证公开详情、作者列表与数量恢复，列表不因重发置顶。
V21 新建重发审计表并扩展发布 Outbox 的 CONTENT_REPUBLISHED 类型，已在本机执行；V1～V20 保持原样，无新增依赖。
最终在源码哈希一致的 `.local-dev/structure-verification/content-republish-final/backend` 执行
`mvn -B -ntp clean verify`：2375 项全部通过，其中 1062 项单元/Web、1313 项真实集成，零失败、零错误、零跳过，打包成功。
592 个源码/资源/pom 文件与工作区一致；原 39 个错误契约操作保留，新增后覆盖 40 个操作。
日志 `.local-dev/structure-content-republish-final.log`，统计 `.local-dev/content-republication-task/verification-summary.json`。
原始 OpenAPI、真实 infra/.env 和 IDE 设置未变；保留此前未提交改动，未提交、推送或启动独立开发后端。
本任务未推进删除恢复、媒体处理 worker 或 Outbox 投递器/消费者。

## 2026-11-12 作者主动撤回公开内容验证

新增 `DELETE /api/v1/creator/contents/{contentId}/publication`，规则与调用链见[撤回公开](content-publication-withdrawal.md)。
按红→绿→重构验证无响应体204、历史发布稿与普通草稿保留、PENDING失效和IN_REVIEW终结、
旧版本重复无副作用、下架保护、私有写入权限、事务回滚、审计和Outbox、独立配额及与真实审批接口并发。
实际审批成功结果的幂等重放不会把后来撤回的内容重新公开。
V19新增撤回审计与发布Outbox并扩展审核失效事件类型；V20补齐SQL CHECK条件必填版本的显式非空约束。
两份迁移均已在本机执行，V1～V18保持原样，无新增依赖，不推进投递器、消费者或其他接口。
最终在源码哈希一致的 `.local-dev/structure-verification/publication-withdraw-final/backend` 执行
`mvn -B -ntp clean verify`：2276项全部通过，其中1018项单元/Web、1258项真实集成，零失败、零错误、零跳过，打包成功。
576个源码/资源/pom文件与工作区一致；原有38个操作错误契约保留，新增后覆盖39个操作。
日志 `.local-dev/structure-publication-withdraw-final.log`，统计 `.local-dev/publication-withdrawal-task/verification-summary.json`。
保留此前未提交的新草稿实现；本轮未提交、推送或启动独立开发后端。

## 2026-11-12 从历史修订创建新草稿验证

新增 `POST /api/v1/creator/contents/{contentId}/drafts`，规则与调用链见[历史修订新草稿](content-draft-creation.md)。
按红→绿→重构验证显式和默认来源、原文及有序引用复制、单一工作修订、版本冲突、公开状态保留、
24小时成功快照重放、处理租约接管、同键请求指纹、资格复核、事务失败回滚、并发与独立配额。
V18新增独立幂等表并已在本机执行；V1～V17及已有依赖保持原样，成功重放免配额。
文档冷启动实测16.2秒超过旧测试15秒请求时限，契约测试等待上限改为45秒，保留所有契约断言。
续跑时恢复本机已停止的开发中间件，未重建容器或删除数据卷。
最终在源码哈希一致的 `.local-dev/structure-verification/draft-create-final/backend` 执行
`mvn -B -ntp clean verify`：2197项全部通过，其中983项单元/Web、1214项真实集成，零失败、零错误、零跳过，打包成功。
560个源码/资源/pom文件与工作区一致；原有37个操作错误契约保留，新增后覆盖38个操作。
日志 `.local-dev/structure-draft-create-final.log`，统计 `.local-dev/draft-creation-task/verification-summary.json`。
未提交、推送或启动独立开发后端；历史修订查询、删除恢复和媒体处理worker未随本接口推进。

## 2026-11-11 公开媒体短时读取验证

新增 `GET /api/v1/contents/{contentId}/media/{assetId}/access`，规则与调用链见[公开媒体取址](content-media-access.md)。
按红→绿→重构验证当前发布修订关联、归属及用途、目标非READY的409、其他素材异常的404、READY结果绑定、
事务外MinIO HEAD、最终共享锁重检、严格修订绑定、可选认证、独立签名配额、配置与OpenAPI。
真实下载图片字节并验证视频结果对象的Range GET；确认公开读取不写敏感预览审计、业务数据或Outbox。
最终在源码哈希一致的 `.local-dev/structure-verification/published-media-final/backend` 执行
`mvn -B -ntp clean verify`：2097项全部通过，其中945项单元/Web、1152项真实集成，零失败、零错误、零跳过，打包成功。
543个源码/资源/pom文件与工作区一致；原有36个操作错误契约保留，新增后覆盖37个操作。
无新增依赖或迁移，V1～V17、infra/.env及IDE设置保持原样。未提交、推送或启动独立开发后端。
日志 `.local-dev/structure-published-media-final.log`，统计 `.local-dev/published-media-task/verification-summary.json`。

## 2026-11-11 指定作者公开作品验证

新增 `GET /api/v1/users/{userId}/contents`，行为、调用链与查询数量见[作者公开作品](content-author-list.md)。
按红→绿→重构完成作者隔离、稳定键集分页、游标用途绑定、参数及认证边界、共享配额和依赖错误处理。
新增并执行 V17 作者公开排序索引，EXPLAIN 验证可按作者及公开状态定位后倒序扫描；V1～V16 保持原内容。
最终在源码哈希一致的 `.local-dev/structure-verification/author-list-final/backend` 执行
`mvn -B -ntp clean verify`：1999 项全部通过，其中 909 项单元/Web、1090 项真实集成，零失败、零错误、零跳过，打包成功。
531 个源码/资源/pom 文件与工作区一致；原有 35 个操作错误契约保留，新增后覆盖 36 个操作。
日志 `.local-dev/structure-author-list-final.log`，统计 `.local-dev/author-content-task/verification-summary.json`。

## 2026-11-11 公开内容列表验证

新增 `GET /api/v1/contents`，分页、游标、批量读取与配置见 [公开内容列表](content-list.md)。
最终在源码一致的隔离副本执行 `mvn -B -ntp clean verify`，1854项全部通过：
841项单元/Web、1013项真实集成，零失败、零错误、零跳过，打包成功。
新增并执行V16公开排序索引，既有迁移不改写；列表与详情共享公开查询额度。
日志 `.local-dev/structure-list-final-v2.log`，统计 `.local-dev/public-list-verification-summary.json`。

## 2026-11-11 已发布内容详情验证

新增 `GET /api/v1/contents/{contentId}`，行为与分包见 [已发布内容浏览](content-browse.md)。
在源码哈希一致的隔离副本运行 `mvn -B -ntp clean verify`，1805 项全部通过：
821 项单元/Web、984 项真实集成，零失败、零错误、零跳过，打包成功。
日志 `.local-dev/structure-public-final.log`，统计 `.local-dev/public-content-verification-summary.json`。

## 2026-11-11 内容用例依赖收口验证

本轮消除审核与共享层对 `content.workspace` 的 8 处直接引用：读取 Mapper 和行锁 Mapper 归入
`content.mapper`，后台详情使用共享 Reader，提交审核直接装配已加锁内容；公共校验改用
`ContentDraftData`，共享查询限流器及参数归入 `content.ratelimit` / `content.config`。
本人审核局部投影改名 `OwnedReviewRow`，17 个参数绑定测试改名为 `*PropertiesTest`。

`ContentModuleBoundaryTest` 先真实失败并列出上述引用，迁移后变绿；全部单元/Web 测试通过后完成命名整理。
最终在源码哈希一致的 `.local-dev/structure-verification/boundary-final/backend` 执行
`mvn -B -ntp clean verify`：1742 项检查通过，其中 790 项单元/Web、952 项真实集成，零失败、零错误、零跳过，打包成功。
日志为 `.local-dev/structure-boundary-final.log`，统计为 `.local-dev/content-boundary-verification-summary.json`。

全部 124 条 Mapper SQL 注解内容保持一致；原有锁顺序、事务和幂等边界保持原规则。
工作区、列表和本人审核反馈继续共享 `app.content.query` 及 `{content-query}` 配额。
V1～V15、原始 OpenAPI、固定错误契约、应用及环境示例配置、IDE 设置均未改动；本轮未提交或推送。

## 2026-11-11 结构整理验证

完成全局时钟与调度归位、模块参数集中注册、内容共享数据模型与数据库参数拆分、公共 Redis 限流执行器、
账号事务访问入口、`content.workspace` / `content.review` 分组、测试清理复用及文档拆分。

按红→绿→重构推进：先验证独立时钟、异常限流结果和账号事务边界，再在原有业务测试保护下迁移。
最终执行 `mvn -B -ntp clean verify`，通过 1741 项检查：789 项单元/Web、952 项真实集成，
零失败、零错误、零跳过，打包成功。集成测试使用真实 MySQL、Redis、MinIO，串行执行并关闭邮件投递和上传清理。

IDE 曾占用共享 `backend/target` 并生成 ECJ 错误 class，因此最终验证在
`.local-dev/structure-verification/final/backend` 的源码副本中执行；复制前后以及验证结束后均核对源码、资源、
测试与 pom 的 SHA256，确认与当前工作区一致。日志为 `.local-dev/structure-final.log`。

V1～V15、`application.yml`、`infra/.env.example`、固定错误契约、原始 OpenAPI 和
`.vscode/settings.json` 均保持原内容，所有原有 Mapper SQL 注解内容也保持不变。
本次没有提交、推送或启动独立开发后端；不据测试进程声明开发端口在线。

[返回后端入口](../README.md) · [结构与边界](../STRUCTURE.md)

文中 `src/` 路径相对 `backend/`；验证数字为对应开发阶段的历史记录，当前结果以最新执行为准。

## 接口行为与约定

### Spring Boot 4 注意事项（本次踩过的坑）

1. **Web starter**：`spring-boot-starter-web` 已标记 deprecated，使用 `spring-boot-starter-webmvc`。
2. **Flyway**：Boot 4 把自动配置拆到独立模块，必须用 `spring-boot-starter-flyway`；只加 `flyway-core` 不会执行迁移。
3. **Jackson 3**：`spring.jackson.serialization.write-dates-as-timestamps` 在 Jackson 3 已不存在，配置它会导致启动失败；Jackson 3 默认输出 ISO-8601 字符串。
4. **HTTP 422**：使用 `HttpStatus.UNPROCESSABLE_CONTENT`；`UNPROCESSABLE_ENTITY` 在 Spring 7 已过时。
5. **Git Bash 环境变量**：不要把 `/` 作为环境变量值，MSYS 会把它转成 Git 安装目录下的路径。RabbitMQ vhost 使用 Spring 默认的 `/`，不通过 `.env` 传入。

JSON请求严格匹配契约类型：数字和布尔值不会自动转换成字符串，枚举只接受名称，不接受数字序号或序号字符串。
类型不匹配返回400 `MALFORMED_REQUEST`；缺失、空值和字符串内容继续按各接口的字段规则校验。
Swagger显式声明验证码申请202、注册201和登录201，与实际成功响应一致。
