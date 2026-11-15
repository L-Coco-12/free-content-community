# 图片自动校验

[上传与媒体](media.md) · [结构](../STRUCTURE.md)

完成接口继续返回 202 受理结果。`MediaProcessingWorker` 自动消费持久化的 `MEDIA_PROCESSING`
任务，不需要客户端轮询触发。成功时在同一事务中提交任务 `SUCCEEDED`、进度 100、素材 `READY`、
实际 MIME、字节数、宽高及独立结果对象绑定。失败原因通过原任务查询接口读取。

## 处理过程

1. `MediaProcessingTransactionService.claim` 取得任务行锁，检查到期时间、执行次数、已完成上传、
   归属、素材状态和存储配置。为本次执行生成租约令牌与独立结果键，在写文件前登记结果记录。
2. 事务外用 `MediaProcessingStorage.read` 下载源文件。GET 携带完成时记录的版本号（如有）与
   ETag 条件，并核对响应的类型、大小和最后修改时间；流读取也有大小上限。
3. `ImageValidator` 从实际字节探测格式，与声明 MIME 严格比较，校验可选 SHA-256、文件容器
   完整性和解码结果。每帧分配像素内存前先核对尺寸和预算；GIF 使用逻辑画布计算预算，
   WebP 扩展格式使用全局画布，避免局部帧绕过限制。逐帧解码，最多探测到第一个超额帧。
4. `MediaProcessingStorage.write` 将校验的同一份字节写入
   `private/{ownerId}/{assetId}/ready/{本次令牌}`，再 HEAD 核对结果大小、类型和 ETag。
   不裁剪、不压缩、不重编码，保留原格式、元数据及动画。客户端的源 PUT 地址不能写这个结果键。
5. `succeed` 重新锁定任务、上传及素材，复核未过期令牌、源快照、状态及结果登记，再一起提交
   READY 与成功任务。旧执行者不能覆盖新执行者的状态或结果。

ETag 是对象标识，不当作文件摘要使用；SHA-256 由服务端对实际读取的字节计算。
本通路是文件格式与资源校验，不是查毒、图片内容审核或隐私元数据清除。

## 支持与资源限制

JPEG、PNG、GIF 使用 ImageIO；WebP 使用 `com.twelvemonkeys.imageio:imageio-webp:3.15.3`。
插件通过 ImageIO SPI 注册纯 Java 解码器，无需本地 DLL。已用真实有损、无损透明、动画 WebP
测试文件执行解码；测试图像由本地生成，不引入第三方图片素材。

配置归 `app.media.processing`，由 `MediaModuleConfiguration` 注册，环境变量见 `infra/.env.example`。

| 参数 | 环境变量 | 默认值 |
| --- | --- | --- |
| enabled | MEDIA_PROCESSING_ENABLED | true |
| poll-delay-millis | MEDIA_PROCESSING_POLL_DELAY_MILLIS | 1000 |
| lease-seconds | MEDIA_PROCESSING_LEASE_SECONDS | 120 |
| max-attempts | MEDIA_PROCESSING_MAX_ATTEMPTS | 3 |
| retry-base-seconds | MEDIA_PROCESSING_RETRY_BASE_SECONDS | 5 |
| orphan-grace-seconds | MEDIA_PROCESSING_ORPHAN_GRACE_SECONDS | 120，至少 60 |
| max-width / max-height | MEDIA_PROCESSING_MAX_WIDTH / MEDIA_PROCESSING_MAX_HEIGHT | 各 8192 |
| max-frame-pixels | MEDIA_PROCESSING_MAX_FRAME_PIXELS | 25000000 |
| max-frames | MEDIA_PROCESSING_MAX_FRAMES | 100 |
| max-total-pixels | MEDIA_PROCESSING_MAX_TOTAL_PIXELS | 100000000 |
| decode-timeout-seconds | MEDIA_PROCESSING_DECODE_TIMEOUT_SECONDS | 30 |

字节上限继续读取 `app.media.upload-policy.max-image-bytes`，默认 10 MiB，不另设上传配额。
存储连接 3 秒、读写空闲 5 秒、单次网络调用总时限 15 秒。结果按已知长度执行单次 PUT，
关闭 SDK 的分片重试，由任务统一重试。租约必须大于解码预算加 45 秒（GET、PUT、HEAD）。
解码时间在帧间及帧后检查，Java 解码器的单次调用不提供强制终止保证；像素预算限制分配规模。
每个应用实例只有一个后台处理线程；调度通过 busy 门闩保持最多一个在途任务，不堆积超时线程
或无限工作队列。调度线程只派发任务，不直接下载和解码。

## 失败、接管与补偿

V25 为任务增加执行次数、可执行时间、租约，并新增 `media_processing_output` 结果登记。
旧 PENDING 任务直接可消费；遗留 RUNNING 无租约或租约过期时可接管。V1～V24 未改写。
任务处理不重新要求作者登录或写入权限：它处理的是已受理文件，素材读取和内容发布仍各自授权。

- 无法识别、损坏、截断：`INVALID_IMAGE`。
- 真实 MIME 与声明不同：`MEDIA_TYPE_MISMATCH`。
- SHA-256 不符：`CHECKSUM_MISMATCH`。
- 尺寸、像素、帧数或解码预算超限：`IMAGE_LIMIT_EXCEEDED`。
- 源文件缺失、替换或快照不符：`SOURCE_OBJECT_CHANGED`。
- 停机或线程中断：`PROCESSING_INTERRUPTED`，按临时故障重试，不判为文件永久损坏。
- 存储配置改变：`STORAGE_CONFIG_CHANGED`。
- 上传或素材状态变化：`RESOURCE_STATE_CHANGED`，保护已有结果，不覆盖其他终态。
- 临时存储故障：指数退避后重试，最多 3 次（可配置），耗尽后 FAILED、retryable=true。
  确定性校验失败直接 FAILED、retryable=false。尚未提供用户主动重试任务的接口。

正常校验失败时任务及 PROCESSING 素材一起 FAILED；不会把假文件设为 READY。
数据库提交失败整体回滚，保留执行租约和结果登记，等待过期接管。

结果写入前已登记对象键，因此进程在 PUT 后崩溃也不会丢失清理线索。补偿先取得任务、素材和
结果记录锁，排除活跃执行、已绑定结果、路径或存储身份不符，再在事务外幂等删除独立结果对象。
删除失败延后重试；删除成功的记录保留，并每天复查一次，回收旧进程恢复后可能产生的迟到写入。
结果记录不应在缺少对象生命周期方案时随意清除。此补偿不删除源对象，不替代取消上传的原清理器。

## 验证与范围

`media/processing/ImageValidatorTest` 覆盖真实格式解码、MIME、摘要、截断、尺寸及动画预算。
`media/config/MediaProcessingPropertiesTest` 覆盖配置绑定和非法边界。
`media/job/MediaProcessingWorkerTest` 验证真实线程中断时的可重试分类。
`media/job/MediaProcessingFlowIT` 使用真实 HTTP、MySQL、Redis、MinIO，覆盖上传完成至 READY、
失败落库、原字节保留、源覆盖、退避、并发、旧令牌、回滚、调度派发和孤立结果回收。

共享开发库的回归应设置 `MEDIA_PROCESSING_ENABLED=false`，新 Worker 测试只显式处理本次创建的任务，
防止测试后台消费其他用户的积压任务。`.local-dev/run-structure-check.ps1` 已设置该隔离变量。

头像用途图片已接通自动校验、[绑定保存](user.md#修改当前用户资料)和[公开头像展示](user-avatar-access.md)。
本次不增加视频、分片处理、转码、MQ 或新的 HTTP 接口。应用重新构建启动后才会运行新 Worker。
