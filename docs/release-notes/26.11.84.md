# GoMusic 26.11.84

三个问题的根因都定位到了，一并修掉。

## ① 一起听：不会自动获取登录状态 / 无法创建房间

**创建房间 100% 失败的根因**：上一版照抄示例 HTML 里的相对路径 `listentogether/room/create`，但示例运行在浏览器里由页面 URL 解析；App 里是 `NM_BASE + path` 直接拼接，而 `NM_BASE`（`https://mapi.myocd.de5.net`）没有尾斜杠 —— 实际请求成了
`https://mapi.myocd.de5.netlistentogether/room/create?...`，域名直接拼错，所有房间接口都发不出去。

- `room/create`、`room/check`、`accept`、`sync/list`、`sync/list/command`、`sync/playlist/get`、`play/command`、`heatbeat`、`heartbeat`、`end` **全部补上前导 `/`**（含 `socialPost` 的 URL 拼接）。
- 进入「一起听」子页自动调一次 `/login/status`（`ltAutoLogin`，只自动一次，不弹打扰性 toast），消息条直接显示「已登录 xx，可以创建房间」；手动「获取登录状态」按钮保留作兜底。
- `ltCreateRoom` 不再要求用户先手点登录：发现未登录会自己再取一次登录态，取不到才明确提示；创建按钮也不再因登录态未就绪被禁用。
- `socialCall` 的「像成功」判断收紧：明确业务错误码（301/400/404/460…）不会因响应带 `data` 就被当成成功而不换 method，GET/POST 主备切换现在真的生效。

## ② 播客区域全部失效

**根因**：播客数据层强制 `code == 200` 才继续解析，且所有请求只发 GET。api-enhanced 的 voice workbench 系列（`/voicelist/search`、`/voicelist/my/created` 等）底层是 weapi POST，不同部署会把 `code` 放进 `data` 或干脆只给 `result / voicelists / list`；强制顶层 200 会让整页静默空白，看起来就是「全挂了」。

- 新增 `isOk()` 宽松判断：顶层 `code==200`、`data.code==200`、或响应里带业务数据键（`list / voicelists / radios / voices / programs / records / result / songs / msgs / playlist` 等）都算成功，继续容错解析。
- 播客与私信全部接口改走 `Netease.socialCall`：先按文档例子的 method 发一次，不像成功就换另一种 method 重试（cookie 在 query 或 form body 都能带）。
- 加载不再静默空白：新增「接口没有返回内容…」错误条 + 「重试加载」按钮；搜索无结果也给出「可能是接口未返回数据」提示；首次请求失败不再被 `recentDj/myCreated` 非空判断锁死，可反复重试。

### 创建播客
**如实说明**：直接查了 api-enhanced 的 `module/` 目录，播客相关只有 `voicelist_search / detail / list / list_search / my_created / trans` 与 `voice_delete / detail / upload`，**没有 `voicelist_create`**；你给的文档里也没有创建播客接口。所以：

- 播客页「我创建的播客」新增「＋ 创建播客」表单（名称、简介、分类 id 可选），按常见命名主备探测 `/voicelist/create`、`/voice/list/create`、`/voicelist/add`、`/voice/workbench/voicelist/create`，任一成功就解析 `voiceListId` 并刷新列表。
- 全部失败时**明确提示「当前服务端没有创建播客接口」**，不会静默假装建好；旁边提供「网页端创建」按钮跳转网易云，创建完回来点「重试」即可看到。
- 另加「重试」按钮与「暂无创建的播客（可点上方…）」的可操作空状态。

## ③ 输入栏不随虚拟键盘上浮

**根因**：`enableEdgeToEdge()` 下系统不再自动为 IME 调整布局，而 Compose 内容没有消费 IME inset；`adjustResize` 虽然已在 Manifest 里，但输入框仍被键盘盖住。

- 主屏最外层根布局加 `Modifier.imePadding()`，一处覆盖聊天输入、私信附言/uid、播客搜索与创建表单、一起听房间/歌单输入、分享选人弹层、以及所有 `GoSheet` 内的表单。
- `MainActivity` 显式 `WindowCompat.setDecorFitsSystemWindows(window, false)`，确保 `imePadding()` 能拿到 IME inset；Manifest 的 `adjustResize` 保留。
- 未改任何页面自己的 `statusBarsPadding()` / `navigationBarsPadding()`，播放器与横屏布局不受影响。

## 说明与限制
- 这轮附带的接口/文档链接资源解析服务全部失败；播客 method 与响应结构的判断依据是 api-enhanced `module/` 源码与 GitHub 上 `/voicelist/list`、`/voice/detail` 的模块实现，未做实机联调（沙盒 403），以 CI 编译＋签名校验为准。
- 创建播客接口服务端确实没有，应用内创建会明确报错并引导网页端；若你的部署自建了创建接口，告诉我路径与参数即可接上。
