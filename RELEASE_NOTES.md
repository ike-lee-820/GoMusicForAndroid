# GoMusic 26.11.88

## ① 删除 log 输出

`NmLog` 整体改成空壳：**不再创建 `Download/gomusic/log/` 日志文件、不再启动常驻写线程、不再输出任何请求/响应/崩溃栈**。198 个日志调用点保留同名空实现，行为等价于没有日志，但不用改动上百处调用带来回归风险。设置里「请求日志：路径」那行同步删掉；`CrashHandler` 不再安装自定义处理器，系统原本的崩溃行为不变。

## ② 删除播客界面，原页面改成「电台」

底栏第一项「播客」→「电台」，`PodcastUi.kt` 整体删除，新页 `RadioPage` 覆盖用户列出的**电台相关**接口，按子页组织、每个接口单独按需加载（不一次并发十几个请求）：

- **发现**：banner、推荐电台、推荐节目、推荐电台节目、个性推荐、今日优选、热门电台、节目榜、24 小时节目榜、24 小时主播榜、主播新人榜、最热主播榜、新晋/热门电台榜、付费精品、付费精选、电台排行榜
- **分类**：分类列表、推荐类型、非热门类型、分类推荐（可填 type）、类别热门电台（可填 cateId）
- **我的**：用户电台 `/user/dj`、用户创建电台 `/user/audio`、订阅列表 `/dj/sublist`、广播收藏、DIFM 收藏频道
- **广播**：分类/地区、全部电台（可填 categoryId）、电台信息（可填 id）、收藏/取消收藏 `/broadcast/sub`
- **DIFM**：频道分类、播放列表（可填 channelId）、频道收藏/取消收藏
- **声音**：`/voicelist/list/search` 声音搜索（可按名称/状态筛选）、`/voicelist/trans` 排序（填 radioId,programId）
- **搜索**：`/search?type=1009` 独立搜电台
- **详情**：点电台进详情（`/dj/detail` + `/dj/program`），节目用 `mainTrackId` 走已有网易云音源链播放；订阅/退订 `/dj/sub` 在列表与详情页都能操作

响应包字段在不同部署里差别很大，统一从 `data / result / playlists / programs / recommend / channels / …` 等常见数组键里捞对象再映射，单个接口换字段不会整页空白；请求走双 method 主备。

**明确不做**（按「无关不实现」）：评论系列（`/comment*`、`/comment/like` 等）、用户动态 `/user/event`、分享到动态 `/share/resource`、资源点赞 `/resource/like`、MV 相关、cloudsearch 综合等，都没塞进电台页。

## ③ 歌单更换封面 + 复原封面

- 已有「更换封面」走 `/playlist/cover/update` 的 multipart `imgFile`（带 id / imgSize / imgX / imgY）
- 新增**复原封面**：第一次更换前自动记下原始封面 URL，并后台把原图字节备份到应用私有目录；点「复原封面」把本地改回原图，**同时把备份字节重新上传**，云端也回到原样
- 连续换多张图不会把「上一张自定义图」当成原图；云端不可写 / 没拿到原图字节时明确提示「本地已复原」，不假装云端也好了

## ④ 搜索增加「歌单」选项

分类从「综合 / 歌手 / 专辑 / 歌曲」扩成五个，新增**歌单**（`/search?type=1000`）。回车时歌单作为第四路独立并发，不塞进三路主链、不拖慢首屏；综合页多一个「歌单」横排分区，歌单分类页展示全量结果并支持触底翻页，点开复用已有歌单详情页（`nmKind=playlist`）。

## ⑤ 首页新增六个推荐位

推荐歌单 `/personalized`、每日推荐歌单 `/recommend/resource`、网友精选碟 `/top/playlist`、精品歌单 `/top/playlist/highquality`、推荐 MV `/personalized/mv`、最新 MV `/mv/first`。六路各自独立并发，一路失败不影响其余；歌单卡片点开详情页，MV 卡片取直链后进整屏视频页。

> 说明：26.11.87 在 CI 阶段因 `SearchUi.kt` 少 import `LazyRow` 编译失败、未发布；该修复（补 import）已并入本版，本版是上述全部改动的一次性正式发布。

## ⑥ 所有链接改在应用内 WebView 打开，UA / cookie 强制固定

新增统一网页容器 `WebLinkPage`：`Screen.WebLink(url)` 进页面栈，`vm.openInternalLink(url)` 是**唯一**的网页打开入口，不再调系统浏览器。收口范围：

- **私信里的链接**（`openChatLink`）：普通 URL 不再 `ACTION_VIEW` 丢给浏览器，全部进应用内 WebView
- **验证码登录 / 安全验证**：风控 `-462` 返回的易盾验证页，`NmUi` 的「打开验证页」改走内部 WebView（文案同步改成「在应用内完成验证」）
- **应用外更新下载页**：`UpdateUi` 的下载页链接也进 WebView；其中 APK 等下载类链接由 `DownloadListener` 交给系统下载/安装（下载文件不是「打开网页」）
- **一起听分享链接**：仍按上一版解析 `roomId/inviterId/songId` 自动加入房间（业务动作保留），同时链接本身也在 WebView 内打开，满足「所有链接」；页面内跳转一律留在 WebView，只有 `tel:` / `weixin:` / `intent:` 等非 http(s) scheme 才交系统

**UA 固定**（写死常量，且 `onPageStarted` 每次再设一遍防被改）：
`com.netease.cloudmusic/9006035 (Linux; U; Android 14; zh_CN_#Hans; HEY2-W09; Build/HONORHEY2-W09; Cronet/72.0.3626.122)`

**cookie 固定**：登录时用当前账号的 `prefs.nmCookie`；未登录时异步取游客 cookie（`/register/anonimous`，带 10 分钟缓存）。打开前先 `CookieManager.removeAllCookies` 清掉残留的其它账号，回调里再把 cookie 串逐条注入 `music.163.com` / `st.music.163.com` / `163.com` / `reg.163.com` / `mapi.myocd.de5.net` 等域并 `flush()`，最后才 `loadUrl`——全程不阻塞主线程。开启 JS、DOM Storage、第三方 cookie、混合内容，支持缩放；顶栏有返回/刷新/复制链接与加载进度，系统返回键先在网页内回退、退到头才关页。

## 说明与限制
这轮附带的 30 个文档链接与全部接口 URL 资源解析服务均失败，接口参数按你贴在正文里的说明实现；`mapi.myocd.de5.net` 在沙盒返回 403，**未做实机联调**，以 CI 编译＋签名校验为准。游客 cookie 依赖 `/register/anonimous` 可用，取不到时 WebView 会以空 cookie 打开并在页面里按服务端要求走自己的验证。
