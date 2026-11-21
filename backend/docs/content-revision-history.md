# 本人内容修订历史

`GET /api/v1/creator/contents/{contentId}/revisions?page=1&pageSize=20`，operationId=listMyRevisions。
成功200，data为RevisionPage：items、page、pageSize、total。items复用完整Revision模型，包含
标题、正文、修订号、状态、素材/标签ID和创建/更新时间，不替换为摘要模型。

从列表打开某一版时，调用 `GET /api/v1/creator/contents/{contentId}/revisions/{revisionId}`。
详情使用相同的本人读取权限和查询额度，成功返回单个 `Revision`。服务端先确认作品归属，再用
内容和修订两个 ID 定位版本，最后在同一只读事务中读取该版本的素材和标签引用。旧版、当前草稿及
回收站中保留的修订均可读取。作品不属于当前用户、修订不存在或两个 ID 不匹配时统一返回
`404 RESOURCE_NOT_FOUND`。详情路径无需查询参数或请求体。

page默认1、正整数且无契约上限；pageSize默认20、允许1～100。使用BigInteger解析page，先比较
偏移量与total，超出末页返回空items和真实total，避免巨大页码溢出Long。整数语法错误400，
范围错误422。只接受page/pageSize，未知、重复参数及请求正文400。contentId为1～64位
ASCII字母、数字、下划线及短横线，格式错误422。

## 本人权限与读取范围

复用工作区读取规则：有效身份、邮箱已验证、当前USER创作角色，事务内通过AccountAccessService
共享锁复核账号、authVersion和角色。WRITE_BLOCKED保留读取权，DISABLED为403；认证版本变化401。
管理员、审核员角色不扩大本人范围。他人内容和不存在内容均404，先确认归属再统计和读取修订。
本人回收站、撤回、下架或未发布内容的历史仍可读取。

按 `(revisionNumber,id)` 倒序，最新修订在前，不按updatedAt或创建时间重排。
包含DRAFT、IN_REVIEW、APPROVED、REJECTED、CANCELLED。数据库仍只允许同一作品最多一个
DRAFT/IN_REVIEW工作修订；当前草稿未填写标题、正文时返回空字符串。
历史查询不依赖当前工作或公开指针，保留历史修订及其引用关系。

素材返回ID，保持引用位置顺序；标签停用或素材不再READY不会让历史快照消失。实际媒体预览
仍由独立取址接口授权并校验状态，本接口不签名、不访问MinIO、不返回存储路径。
列表不增加审核原因、审核ID或新的公开权限字段。

## 实现与一致性

ContentWorkspaceController#listMyRevisions → ContentWorkspaceService接口/impl →
ContentWorkspaceTransactionService#listRevisions → ContentReadMapper → ContentWorkspaceReader#revisions。
入口、分页DTO和测试归content.workspace，复用content共享Mapper、修订投影与本人查询限流。
身份/路径及页码解析复用已有工作区实现，不把业务授权搬进公共账号Reader。

同一个readOnly + REPEATABLE_READ事务中完成归属、total、分页行以及素材/标签关联读取。
正常非空页固定5次业务SQL：本人内容、修订总数、分页修订、批量素材引用、批量标签引用；
账号与角色复核不包含在此计数。空页只做归属与计数。不逐修订调用详情，不重复查询分页行。
ContentWorkspaceReader为工作区列表及修订历史共用关联装配，保持既有工作/发布/冻结状态校验。

每次请求内部保持一致快照；跨请求新增修订可能改变页码位置，本页码接口不承诺跨页固定快照。
数据读取故障或无法完整投影返回503，不静默跳过修订、不吞成空页。没有账号或作品版本、
审核、发布指针或历史正文写入。可复用V10的uk_revision_number(content_id,revision_number)，
无新增迁移、依赖或配置，V1～V27不改写。

## 配额与验证

共享app.content.query / `{content-query}`，与本人工作区、创作列表和审核反馈合计配额：
来源120次/分钟、用户60次/分钟。非法参数不计数；合法空页、重复页和授权后的资源查询计数；
获准后的依赖失败不退还。429附正整数Retry-After，沿用CONTENT_QUERY_*环境变量。
成功和失败带X-Request-Id及Cache-Control: no-store，错误集合与原始OpenAPI一致。

ContentRevisionHistoryFlowIT使用真实HTTP/MySQL/Redis覆盖全部状态、完整字段、修订号排序、
默认/最大/超大页码、回收站读取、本人范围、认证后账号/角色变化、共享限流、固定批量读取、
新增修订并发快照及生成的OpenAPI模型。ContentRevisionHistoryServiceTest覆盖解析和依赖错误隔离。
工作区、创作列表、后台及审核详情纳入共享投影回归。
