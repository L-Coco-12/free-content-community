# 后台查询全部状态内容

[返回后端入口](../README.md) · [结构与边界](../STRUCTURE.md) · [本人工作区](content-workspace.md) · [审核列表](content-review.md)

`GET /api/v1/admin/contents` 对应 `listAdminContents`，返回 200 + `ApiResponse<WorkspacePage>`。
无请求体，不要求 Idempotency-Key，响应 Cache-Control=no-store。

```http
GET /api/v1/admin/contents?page=1&pageSize=20&authorId=user_1&lifecycle=DELETED
Authorization: Bearer <审核员或管理员凭证>
```

## 权限和查询范围

统一后台授权入口分别登记已实现的 GET 集合和单个内容路径，允许 MODERATOR/ADMIN；普通用户、其他方法和未实现子路径继续拒绝。
事务内用账号及角色共享锁重新核对 authVersion、邮箱验证和当前后台角色。
不要求 USER 创作角色，WRITE_BLOCKED 仍可读取，DISABLED 禁止；不能仅凭认证时的旧角色放行。

默认查询全平台、全部生命周期及发布状态，包含操作人员本人、其他作者和已禁用作者的内容。
可以返回未发布工作稿、旧发布稿、已撤回、下架和回收站内容；后台读取不会使它们公开。
本接口按 contentId 查询内容聚合，审核列表仍按 reviewId 查询审核记录，不将一篇作品的多次审核展开为多篇内容。

## 参数、筛选和分页

| 参数 | 规则 |
| --- | --- |
| page | 默认 1，正整数，不增加契约外页码上限 |
| pageSize | 默认 20，范围 1～100 |
| authorId | 可选；1～64 位字母、数字、下划线或短横线 |
| lifecycle | 可选 ACTIVE/DELETED；省略查全部 |
| publicationStatus | 可选 UNPUBLISHED/PUBLISHED/WITHDRAWN/TAKEN_DOWN；省略查全部 |

三个筛选条件按 AND 组合，COUNT 与分页 SQL 共用相同条件。
不存在但格式合法的 authorId 返回 200 空页，不返回 404。
排序固定为 updatedAt DESC,id DESC；分页项和 total 均基于筛选后的集合。
超出最后一页返回空 items，保留 page、pageSize 和真实 total。BigInteger 在确认偏移小于 total 后才转换成 long，超大页码不会溢出。

未知或重复参数、不可解析的分页整数、不支持或空的枚举值返回 400 MALFORMED_REQUEST。
分页数值越界（含超大 pageSize）、非法或空 authorId 返回 422 VALIDATION_ERROR，附 query 位置和字段提示。
不支持 kind、关键词、审核状态、任意排序、expectedVersion 或游标参数。
参数不合法时不消耗配额，也不执行查询。

## 返回的工作区

items 使用完整 ContentWorkspace，包含作者 ID、内容类型、生命周期、发布状态、当前聚合版本、
workingRevision、publishedRevision、activeReviewId 及创建、更新、删除时间。
工作修订与历史发布修订分别保留自身正文、状态和有序素材/标签 ID，允许读取未 READY 素材及停用标签的引用。
只读取当前工作/发布指针，不替换成最近一条历史修订，也不返回全部历史修订。
指针绑定或修订状态异常返回 503，不补造空工作区或静默过滤后返回错误 total。

WorkspacePage 已从 content.workspace.dto 移至 content.dto，共享给本人列表及后台列表。
JSON 字段、required 字段、Schema 名称及分页规则保持原样；本人列表仍默认 ACTIVE，读取范围仍仅限本人。

## 调用链、批量读取及一致性

业务组件统一位于 `content.moderation`：

1. `AdminContentQueryController#list`：只接受契约五个查询字段，拒绝重复参数，调用业务接口并包装响应。
2. `AdminContentQueryServiceImpl#list`：身份、分页、作者和枚举校验，获取独立配额，数据库及事务异常转换为 503。
3. `AdminContentQueryTransactionService#list`：readOnly + REPEATABLE_READ 事务中重新授权，读取总数、当前页并装配完整工作区。
4. `AdminContentQueryMapper`：共享筛选 SQL，按更新时间及 ID 倒序查询内容。
5. `ContentWorkspaceReader#workspaces`：批量读取去重修订、素材引用和标签引用，不循环调用本人或公开详情用例。

非空页最多 5 次业务 SQL：COUNT、内容页、修订、素材引用、标签引用；全页没有修订指针时只需前两次。
无匹配项或超末页只执行 COUNT。
上述数量不包含认证及事务内资格检查。
COUNT、页面和修订引用来自同一个只读快照，避免并发删除、编辑或插入导致一次响应跨版本拼接。
分页跨请求不构成永久快照；后续请求会看到新的提交状态。

本接口不修改内容、修订、审核、时间或版本，不写业务审计或 Outbox，不增加阅读量，也不执行 MinIO HEAD 或签发读取地址。
单个内容读取见[后台内容详情](content-admin-detail.md)，运营处置见[强制下架](content-take-down.md)；查询本身不改变状态。

## 独立限流、错误与验证

`app.content.admin-query` / `{content-admin-query}`：来源 120 次/分钟、操作人员 60 次/分钟，列表与详情合并计算，两个窗口原子检查和计数。
与本人查询、公开查询和后台审核查询隔离；合法空页、重复读取及业务失败可计数。
429 附正整数 Retry-After；Redis 故障返回 503，超限或限流不可用不进入内容查询。

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| ip-per-minute-limit | CONTENT_ADMIN_QUERY_IP_PER_MINUTE_LIMIT | 120 |
| user-per-minute-limit | CONTENT_ADMIN_QUERY_USER_PER_MINUTE_LIMIT | 60 |
| redis-key-prefix | CONTENT_ADMIN_QUERY_REDIS_KEY_PREFIX | fc:content: |

配额必须为正整数，前缀不能空或包含花括号；非法配置阻止启动。
错误集合为 400/401/403/422/429/500/503，不从其他查询用例引入 404、409 或游标错误。
列表首次加入固定错误契约时覆盖 43 个操作；后台详情加入后当前覆盖 44 个，原有操作及原始 openapi.json 保留。
无新增依赖或迁移，V1～V23、真实 infra/.env 和 IDE 设置保留。真实验证记录见[开发与验证](development.md)。
