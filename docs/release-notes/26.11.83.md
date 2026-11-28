# GoMusic 26.11.83

## 一起听：从局域网替代方案改成网易云官方房间协议

26.11.82 因拿不到协议正文，做的是局域网 HTTP 主机模式（`/state` 拉状态）。
本次按你贴的主机模式示例 HTML 与 #1676 Issue 全文重做，**删除**整个
`ListenTogetherHost` / `ListenTogetherGuest` / `RoomState` 局域网服务，改为
对接官方房间接口。

### 接入的接口（按示例与文档）
| 动作 | 接口 |
| --- | --- |
| 登录态 | `/login/status`（userId / nickname） |
| 创建房间 | `listentogether/room/create` → `roomInfo.roomId` |
| 房间检查 | `listentogether/room/check`（joinable / status） |
| 加入房间 | `listentogether/accept`（roomId + inviterId） |
| 房间状态 | `/listentogether/status`（inRoom + roomInfo.roomUsers） |
| 同步歌单 | `listentogether/sync/list/command`（REPLACE，失败退文档里的 `sync/list`） |
| 读取歌单 | `listentogether/sync/playlist/get`（data.playlist.displayList.result） |
| 播放指令 | `listentogether/play/command`（PLAY / PAUSE / GOTO / seek，progress 毫秒） |
| 心跳 | `listentogether/heatbeat`（文档拼写，另兼容 heartbeat），每首歌开始时 + 每 15 秒 |
| 结束房间 | `listentogether/end` |
| 歌单 / 歌曲 | `/playlist/detail` + `/song/detail` |

- 分享链接按示例手动拼接：
  `https://st.music.163.com/listen-together/share/?songId=歌曲ID&roomId=房间ID&inviterId=创建者ID`
- 创建房间后可在私信会话里点「发起一起听」，自动创建并把链接发给对方。
- `room/create` 示例是 GET、`accept` / `sync/*` / `play/command` / `end` 是 POST，且 cookie 有时在 query、有时在 form body：新增 `Netease.socialPost` 与 `socialCall`，先按示例的 method 发一次，不像成功就换另一种 method 重试，不因部署差异卡住房间流程。
- 响应字段全部容错下钻（`data` / `roomInfo` / `playlist` / `result` / `displayList` 等优先键），创建房间文档示例没有 `code` 字段也能认出成功。
- 播放：点曲目发 `GOTO` 指令后，用应用自己的音源链路播放（`nm<id>` 队列项 + `Netease.audioUrl`），并带 `progress` / `clientSeq` / `formerSongId=-1`；播放 / 暂停 / 同步进度分别下发对应指令。

### 社交页「一起听」子页
按示例重排：消息条、账号信息、创建 / 加入房间（房间 ID + 邀请者 ID）、分享链接、刷新房间状态、关闭房间、在线用户列表、播放控制三键、歌单 ID 加载与可点曲目列表。

## 说明与限制
- 本轮附带的 5 个链接（axios.min.js、petite-vue、分享页、`music.163.com/song/media/outer/url`）**资源解析服务全部失败**，但示例 HTML 全文与 Issue 文档正文都由你直接贴出，实现完全按这份正文，未据链接猜字段。
- 接口在沙盒返回 403，**未做实机联调**；以 CI 编译＋签名校验为准。上线后需登录实测各接口的实际 method、响应字段名与 `heatbeat` 拼写。
- `listentogether/sync/list` 与 `/command` 两个路径、心跳 `heatbeat`/`heartbeat` 两种拼写都做了主备，便于按部署实际情况收敛。
- 官方房间的「服务端是否把播放指令真正推给对方」取决于网易云侧，本端只负责按协议下发；客人侧同步播放仍需对方客户端支持，应用内目前提供的是本机播放器控制与房间状态展示。
