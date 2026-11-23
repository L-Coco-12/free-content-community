# 读取运营处置详情

`GET /api/v1/admin/moderation-actions/{actionId}` 对应 `getModerationAction`。具备 `moderation:read`
权限且邮箱已验证的审核员和管理员可按处置 ID 查询。写入受限的运营账号仍可读取。

成功返回 `200` 与 `ModerationAction`。内容下架返回 `CONTENT / TAKE_DOWN`，评论隐藏返回
`COMMENT / HIDE_COMMENT`。接口只接受路径参数，返回执行时记录的目标、原因、操作者、时间和结果版本。
目标后续状态或版本变化时，历史处置仍可读取。

服务在只读事务内复核运营资格，并按主键查询 V24 的 `content_moderation_action` 与 V35 的
`comment_moderation_action`。记录不存在返回 `404`，同一 ID 命中两种记录时返回 `503`。
采用独立的 `app.content.moderation-query` 配额，默认每账号 60 次/分钟、每来源 120 次/分钟。
