# 作者主动撤回公开内容

[返回后端入口](../README.md) · [工作区](content-workspace.md) · [审核](content-review.md) · [新建草稿](content-draft-creation.md)

`DELETE /api/v1/creator/contents/{contentId}/publication?expectedVersion=7` 对应 `withdrawMyPublication`。
需要有效 Bearer、已验证邮箱、ACTIVE 账号及 USER 创作资格，并且内容属于本人。
成功 204 且无响应体，不要求 Idempotency-Key。前端应重新读取工作区以取得当前版本和状态。

## 请求及状态优先级

- contentId 为 1～64 位字母、数字、下划线或短横线；格式非法返回 422。
- expectedVersion 必须是单个查询参数，范围 1～2147483647；不可解析整数返回 400，可解析但越界（含超大整数）返回 422。
- 缺失、重复或未知查询参数返回 400；请求体不能提供或覆盖版本，任何非空请求体均拒绝。
- 身份无效返回 401；账号受限或无创作资格返回 403；内容不存在或非本人统一 404，不泄露 currentVersion。

数据库事务内先重新授权并锁定本人内容，再按以下优先级处理：

| 当前状态 | 行为 |
| --- | --- |
| lifecycle=DELETED | 409 CONTENT_NOT_EDITABLE |
| publicationStatus=TAKEN_DOWN | 409 REMEDIATION_REQUIRED，保留运营下架标记 |
| WITHDRAWN / UNPUBLISHED | 无副作用返回 204，忽略旧版本，不递增版本，不影响后来创建的审核 |
| PUBLISHED | 校验 expectedVersion 及版本上限，再执行实际撤回 |

参数格式和范围仍在进入事务前检查；无副作用成功不绕过授权、本人范围或限流。
若撤回后又重新公开，状态已回到 PUBLISHED，旧版本重试将返回 CONTENT_VERSION_CONFLICT，不能误撤回新的发布。

## 实际撤回的数据变化

内容从 PUBLISHED 变为 WITHDRAWN，version 增加一次、updatedAt 更新；lifecycle 保持 ACTIVE。
publishedRevisionId、原 APPROVED 快照、firstPublishedAt 与 publishedAt 保留，不删除媒体或历史引用。

有 PENDING 审核时，在同一事务中：

1. 校验当前审核和工作修订的指针、内容归属、状态绑定一致。
2. 对应 IN_REVIEW 修订改为 CANCELLED，仅修改状态及更新时间，正文、标题、创建时间和引用保留。
3. 审核改为 INVALIDATED，审核版本增加一次、decidedAt 更新，reviewerId 和 reason 清空。
4. 清空 workingRevisionId 与 activeReviewId，保留原公开修订指针。

没有待审的普通 DRAFT 保持可编辑，工作指针保留。已不公开时即使又提交了新审核，重复撤回也不终结这份新意图。
缺失发布快照、悬空或不一致的审核/工作绑定返回 503，不静默修复，也不产生部分撤回。
内容版本过期或达到上限返回 CONTENT_VERSION_CONFLICT；需要递增但审核版本已达上限时返回 CONTENT_NOT_EDITABLE。

撤回后，新的公开详情、全站/作者列表及媒体取址请求按公开可见条件拒绝或过滤，作者公开数量依据真实可见集合计算。
本人工作区仍保留历史发布稿；被终结的新稿可通过“从历史修订创建新草稿”继续编辑。
之前已经签发的媒体 URL 仍有到期窗口，已下载副本无法回收。

## 调用链、并发及原子事务

1. [PublicationWithdrawalController#withdraw](../src/main/java/com/freecontent/community/content/workspace/controller/PublicationWithdrawalController.java)：检查查询白名单、重复参数和非空请求体，返回无响应体的 204。
2. [PublicationWithdrawalServiceImpl#withdraw](../src/main/java/com/freecontent/community/content/workspace/service/impl/PublicationWithdrawalServiceImpl.java)：检查身份、ID 和版本数值，取得独立配额，再调用事务；数据库和事务异常转换为 503。
3. [PublicationWithdrawalTransactionService#withdraw](../src/main/java/com/freecontent/community/content/workspace/service/transaction/PublicationWithdrawalTransactionService.java)：READ_COMMITTED 事务中重新核对账号、authVersion、邮箱和角色，锁定内容、审核、工作修订及历史发布修订。
4. [PublicationWithdrawalMapper](../src/main/java/com/freecontent/community/content/workspace/mapper/PublicationWithdrawalMapper.java)：以状态、版本和归属条件更新修订、审核及内容，再保存最小审计和 Outbox。

沿用账号及角色 → 内容 → 审核 → 修订的锁顺序，与现有审批和撤销审核一致。
审核和工作修订分别按生成的 pending_content_id、working_content_id 查询，不能仅信任指针。
实际状态变更、版本、指针、审计及事件一起提交，任一步失败整体回滚。

与审批并发时：撤回先提交，则审批的版本/待审检查失败；审批先提交，则旧版本撤回失败，作者刷新后可撤回新的公开稿。
已经成功的审批请求再次幂等重放，只返回原业务结果，不重新执行发布；不能把作者后来撤回的内容重新公开。
同时撤回同一版本的两个请求均可得到 204，但只发生一次实际变更、一条审计和一条撤回事件。

## 最小审计和待发送事件

V19 新增：

- content_publication_withdrawal_audit：按内容与变更后版本唯一，记录作者、旧发布快照、失效审核/修订及前后版本、requestId 和时间。
- content_publication_outbox：记录 CONTENT_WITHDRAWN，按内容、版本和事件类型唯一，状态为 PENDING。

存在待审时，原 content_review_outbox 另写 CONTENT_REVIEW_INVALIDATED；V19 扩展其允许的事件类型，保留全部原有类型。
事件载荷仅包含必要 ID、版本、状态、请求标识和时间，不包含正文、凭证或签名 URL。
重复 204 不增加审计、事件或业务版本，但仍计入请求配额。未新增消息投递器、消费者或通知流程。

V20 显式排除审核版本为 NULL 的情况：SQL CHECK 会放行 UNKNOWN，故“存在失效审核时必须记录审核前后版本”需要 IS NOT NULL 条件。
两份迁移均已在本机执行，V1～V20 均不得回写或删除；本次没有新增依赖。

## 独立限流

app.content.publication-withdrawal / `{content-publication-withdrawal}` 独立于撤销审核和其他内容操作。
默认来源 120 次/分钟、用户 60 次/分钟；合法重复 204 同样计数，失败事务不会退还已使用的请求额度。
非法参数不进入计数。429 带正整数 Retry-After，Redis 故障返回 503，超限或限流不可用不进入撤回事务。

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| ip-per-minute-limit | CONTENT_PUBLICATION_WITHDRAWAL_IP_PER_MINUTE_LIMIT | 120 |
| user-per-minute-limit | CONTENT_PUBLICATION_WITHDRAWAL_USER_PER_MINUTE_LIMIT | 60 |
| redis-key-prefix | CONTENT_PUBLICATION_WITHDRAWAL_REDIS_KEY_PREFIX | fc:content: |

配额必须为正整数，前缀不能为空或包含花括号；非法配置阻止启动。
固定错误契约新增第 39 个操作，原有 38 个操作与原始 openapi.json 保留。
