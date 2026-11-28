# GoMusic 26.11.82

## 界面统一
- 新增 HigType 统一字号：页面大标题 28sp、分区小标题 17sp、导航/弹层标题 17sp、正文 14sp、辅助 12sp、行尾动作 14sp。
- 主页 / 搜索 / 我的 / 社交 / 播客 / 歌单详情 / 简介页 / 设置 / 更新弹层 / 3D 环绕 / 继续播放 / 最近搜索等，全部改用 HigPageTitle 或 HigSectionTitle，不再各写各的 15/16/17/18/20/22/26/28/34。
- 设置入口收成唯一组件 HigSettingAction（44dp 圆形半透明底 + 20dp 齿轮），HigSettingsButton 只做转发；主页、搜索、我的与新增两页都用同一个按钮。
- 底栏（竖屏）与侧栏（横屏）扩成五项，从左到右依次：**播客 / 搜索 / 主页 / 社交 / 我的**，主页仍在正中；侧栏加了滚动，小屏横屏不会被挤掉。

## 新增：社交（私信）
- 私信页面的正式名称是「社交」，三段子页：私信 / 找朋友 / 一起听。
- 登录后调 `/pl/count` 显示私信与通知未读数；`/msg/private` 拉会话列表（limit/offset）；`/msg/private/history` 看聊天内容，用 `before` 取上一页最后一条 time 翻页。
- 发送私信：`/send/text`、`/send/song`、`/send/album`、`/send/playlist`；资源私信的 msg 里是 JSON 字符串，解析全部按优先键容错，兼容不同部署的字段名。
- 找朋友：`/user/detail?uid=` 按数字 uid 精确查资料，昵称走 `/search?type=1002` 兜底；点结果直接开会话。
- 聊天页支持文本、资源私信气泡（区分自己/对方）、刷新、更早消息、发起一起听、分享当前歌曲。
- 应用内私聊分享：播放器更多菜单、搜索结果 ⋮、专辑页 / 云端专辑与歌单页 / 本地歌单页都能把歌曲、专辑、歌单分享给某个会话或直接输入 uid 发送；本地内容没有网易云 id 时退化为文本私信并明确提示。

## 新增：一起听（主机模式）
- 因 #1676 Issue 与 listen_together_host.html 本轮都解析失败，正文里没有网易云官方房间的建连 / 心跳 / 下发协议参数，**没有伪造官方一起听房间**，改为真实可用的局域网主机模式：
  - 主机在手机上起一个极简 HTTP 服务，`/state` 返回当前歌曲、进度、播放状态，随播放轮询实时发布；
  - 房间地址可在会话里点「发起一起听」通过私信自动发给对方；
  - 客人填地址加入后每 2 秒拉一次状态，能看到主机在放什么，并可在本机曲库播放同名歌曲；
  - 两台设备需在同一 WiFi，不依赖网易云账号或任何私有协议。

## 新增：播客页（独立搜索）
- 播客搜索只在本页发起（`/voicelist/search?keyword=`），**主搜索页不搜播客**；首页展示 `/record/recent/dj` 最近播放与 `/voicelist/my/created` 我创建的播客。
- 详情：`/voicelist/detail` 封面/分类/简介，`/voicelist/list` 声音列表，`/voice/detail` 取可播放直链并接入播放器（走本地音频 Uri 路径，不再向服务端取歌）。
- `/voicelist/list/search` 支持按声音名、displayStatus（已发布 / 审核中 / 仅自己可见）筛选；数据层还解析了 radioId、type、voiceFeeType、limit、offset。
- 管理：`/voicelist/trans` 上移/下移，`/voice/delete` 删除，`/voice/upload` multipart 上传声音（字段名 songFile，带 voiceListId / categoryId / secondCategoryId / description / songName）。
- 播放器、专辑、歌单等页面的字号与设置按钮同步收口到统一组件。

## 说明与限制
- 本轮附带的 30 个接口 / 文档链接全部解析失败，接口在沙盒里返回 403，**未做实机联调**；以 CI 编译与签名校验为准。上线后需登录实测各接口的响应字段名。
- 数据结构按容错解析：会话、私信、播客、声音都按 `data / result / list / msgs / voices / radios` 等优先键下钻，某个字段缺失不会整页空白；若你的部署返回结构不同，需要按实际响应调键名。
- `socialUpload` 目前只支持一个文件字段，**`/voice/upload` 的 imgFile 封面同传尚未实现**；coverImgId 也未接。
- 一起听是局域网主机模式的替代实现，不是网易云官方「一起听」房间；官方协议参数补齐后才能对接。
- `/voice/delete` 文档里 ids 写的是「播客 id」，这里按声音 id（voiceId）传并支持逗号隔开多个；`/voicelist/trans` 按文档带上了 limit / offset。
