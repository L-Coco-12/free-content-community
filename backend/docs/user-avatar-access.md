# 公开头像展示

[账号与资料](user.md) · [图片校验](media-processing.md)

公开主页、作品详情、全站列表和作者列表的PublicUser.avatarUrl已接通，返回稳定地址：

```text
https://community.example/api/v1/users/user_1/avatar?assetId=avatar_1
```

地址携带素材ID，换头像后地址变化。CurrentUser仍只返回avatarAssetId；设置页从本人的公开
主页取得avatarUrl。没有头像或引用缺失、归属/用途/状态失效时返回null。公开资料增加一次
LEFT JOIN，批量作者仍批量读取，不逐个查询素材或访问MinIO。

## 已确认的头像读取扩展

`GET /api/v1/users/{userId}/avatar?assetId={assetId}` 是本次确认新增的v1扩展。
原始外部OpenAPI保持不变；本地文档与错误契约快照新增getPublicUserAvatar，
extensionSources记录本文件，原有45个操作定义不变。

允许游客，主动提供Token时必须验证。目标用户的WRITE_BLOCKED、DISABLED状态不隐藏公开头像。
成功返回302、空响应体，Location为有效300秒的MinIO GET地址。302和图片均no-store；签名
绑定实际图片MIME及inline。再次请求同一业务地址会重新检查和签发，无需前端管理签名过期。

| 状态 | 条件 |
| --- | --- |
| 400 MALFORMED_REQUEST | 缺少assetId、重复或额外参数 |
| 401 AUTHENTICATION_REQUIRED | 主动提供的身份无效 |
| 404 RESOURCE_NOT_FOUND | 用户不存在、非当前绑定、归属或用途错误 |
| 409 MEDIA_NOT_READY | 当前绑定素材尚未READY或已FAILED |
| 422 VALIDATION_ERROR | ID格式不合法 |
| 429 RATE_LIMITED | 独立配额耗尽，附Retry-After |
| 503 DEPENDENCY_UNAVAILABLE | 依赖故障、READY证据无效、对象缺失或证据变化 |

读取流程：头像指针及素材共享锁短事务→事务外HEAD→重新加锁复核绑定和对象证据→本地签名。
仅签发private/{ownerId}/{assetId}/ready/{opaqueId}，不退回source。换绑或清空后旧业务地址
停止换取凭证；已签发的存储地址仍可能在原有效期内使用。昵称、简介更新不影响未变化的头像。
读取不修改账号、素材、会话、审计或Outbox。

## 配置与降级

配置为app.user.avatar-access，由UserModuleConfiguration注册，无新依赖或迁移。

| 环境变量 | 默认值 |
| --- | --- |
| USER_AVATAR_READ_URL_TTL_SECONDS | 300 |
| USER_AVATAR_IP_PER_MINUTE_LIMIT | 120 |
| USER_AVATAR_USER_PER_MINUTE_LIMIT | 60 |
| USER_AVATAR_REDIS_KEY_PREFIX | fc:user: |
| USER_AVATAR_PUBLIC_BASE_URL | 空，使用当前HTTP请求根地址 |

独立{user-avatar-access}配额按来源计数，登录身份额外检查用户配额；与主页、内容浏览隔离。
经HTTPS反向代理部署时，公开根地址应配置为https://community.example，如有上下文则加上
/community。显式配置不使用请求Host，也不自行信任X-Forwarded-*。MinIO地址需能被浏览器访问。

存储检查只在图片请求中发生，故障不使主页或作品整体失败。无有效引用时avatarUrl为null；
READY对象暂不可读时地址可能仍存在，但图片请求单独失败。设置页、个人主页、作品详情、首页卡片、右上角及“已登录”个人信息卡片共用
AvatarImage，失败显示名字首字，换源可重试，使用no-referrer，拒绝javascript/data，支持本地blob预览。

前端currentAvatar共享当前登录用户头像，保存资料后的账号版本/头像引用变化会刷新右上角及本人卡片；
不重置内容分页，也不逐个查询其他作者。退出及切换账号取消旧请求，不接收旧账号迟到的头像响应。

PublicAvatarAccessFlowIT验证真实读取、旧地址、身份、独立限流、对象与绑定复核、错误和文档。
ProfileUpdateFlowIT完成真实上传→自动校验→保存→公开资料→302→图片字节验收。
PublicContentListFlowIT验证批量查询及存储故障隔离；前端组件和页面测试验证失败占位及页面保留。
