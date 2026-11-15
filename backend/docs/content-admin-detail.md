# 后台读取指定内容的当前工作区

[返回后端入口](../README.md) · [后台内容列表](content-admin-list.md) · [审核详情](content-review.md) · [本人工作区](content-workspace.md)

`GET /api/v1/admin/contents/{contentId}` 对应 `getAdminContent`，成功返回 200 + `ApiResponse<ContentWorkspace>`。

```http
GET /api/v1/admin/contents/content_1
Authorization: Bearer <审核员或管理员凭证>
```

contentId 为 1～64 位字母、数字、下划线或短横线。无请求体，不接受查询参数，不要求 expectedVersion 或 Idempotency-Key。
非法 ID 返回 422 及 path/contentId 提示；任何查询参数返回 400。参数非法不消耗配额或访问内容。
响应包含 X-Request-Id 和 Cache-Control=no-store。

## 权限与读取范围

统一后台授权入口精确登记 GET 集合和 GET 单个内容路径，允许 MODERATOR/ADMIN。
普通用户及未登录调用拒绝，其他方法和未实现的详情子路径继续拒绝。
业务事务内用账号/角色共享锁重新核对 authVersion、邮箱验证、账号限制及当前后台角色。
无需 USER 创作角色；WRITE_BLOCKED 可读，DISABLED 不可读。

可以读取本人及其他作者的内容，包括 ACTIVE/DELETED 和 UNPUBLISHED/PUBLISHED/WITHDRAWN/TAKEN_DOWN 的全部组合。
其他作者已被禁用不自动阻止后台读取其作品；当前操作者仍须符合后台权限。
合法 ID 不存在返回 404；不会将回收站、未发布或下架内容按公开可见性规则隐藏为 404。

## 返回的当前工作区

复用 ContentWorkspace，包含内容 ID、作者 ID、ARTICLE/VIDEO 类型、生命周期、发布状态、当前聚合版本、
workingRevision、publishedRevision、activeReviewId、创建/更新/删除时间。

- workingRevision 读取工作指针，只接受 DRAFT/IN_REVIEW，可为空。
- publishedRevision 读取历史发布指针，只接受 APPROVED，可为空；存在指针不代表内容仍公开。
- activeReviewId 表示当前待审关联，不在本接口额外返回某次审核的理由或冻结快照。
- 两个修订保留各自正文、状态及有序素材/标签 ID；不要求素材 READY，停用标签引用保留。

本接口不按“最新任意修订”补造工作稿，也不以旧 review.revisionId 替代当前工作指针。
指定审核的冻结稿及审核说明仍通过 GET /api/v1/admin/reviews/{reviewId} 查询。
内容存在但没有当前工作稿或发布稿时可返回两者均为 null；非空指针的关联数据缺失、修订状态不符等完整性异常返回 503。
素材字段只是 ID 引用，不签发媒体地址，不执行 MinIO HEAD 或文件操作。

## 调用链与一致性

1. `AdminContentQueryController#get`：检查路径及查询参数，调用业务接口并包装工作区响应。
2. `AdminContentQueryServiceImpl#get`：身份/ID 校验、共享后台内容查询配额、事务调用，数据库或事务故障收口为 503。
3. `AdminContentQueryTransactionService#read`：readOnly + REPEATABLE_READ 中复核当前后台资格，按 contentId 读取内容。
4. `ContentWorkspaceReader#findContent` / `workspace`：经 content.mapper 读取同一内容的工作修订、发布修订和有序引用，装配共享 DTO。

不调用本人工作区 Controller/Service，也不借公开详情用例扩大可见性。
内容行、版本、指针、修订及引用来自同一只读快照，避免作者保存草稿时出现旧聚合版本与新修订正文混合。
实际集成测试在详情已读取内容行后调用真实草稿保存接口，确认保存成功且详情仍返回一致的旧版本和正文。
后续新请求会读取新提交的状态；详情响应中的 version 不保证直到下架等写入时仍有效，写接口必须再次校验 expectedVersion。

本接口不改变内容、修订、审核、时间或版本，不增加阅读量，不写业务审计或 Outbox。

## 列表与详情共享限流

沿用 `app.content.admin-query` / `{content-admin-query}`：来源 120 次/分钟、操作人员 60 次/分钟。
后台列表、详情、重复读取及合法 ID 的 404 合并计数，不能通过切换入口绕过配额。
与本人查询、公开浏览及后台审核查询隔离；429 带正整数 Retry-After。
Redis 故障返回 503，超限或限流不可用不进入内容读取；数据库故障不会伪装为 404 或空工作区。
没有新增限流参数，环境变量及校验规则见后台内容列表说明。

固定错误契约新增第 44 个操作，原 43 个操作及原始 openapi.json 保留。
无新增依赖或迁移，V1～V23、既有参数配置、真实 infra/.env 和 IDE 设置保持原样。
需要运营处置时另调用[强制下架](content-take-down.md)，详情读取保持只读。真实验证记录见[开发与验证](development.md)。
