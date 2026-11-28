# GoMusic 26.11.94

## 搜索改用 `/cloudsearch`，歌单分类能真正搜出来

接口文档给的是 `/search` 或 `/cloudsearch`（**更全**），`type=1000` 为歌单；`keywords` 必填，`limit` / `offset` 分页。之前"歌单"这个分类从来没调用过 `/cloudsearch`：

- 26.11.87 加的「歌单」分类只打 `/search?type=1000`，而这个部署的 `/search` 对歌单经常返回空 `result.playlists`，于是板块永远「没有找到相关歌单」
- 歌单走的是同步阻塞请求，还拼上整串登录 cookie（URL 接近 4KB），和异步可取消的三路主链不是同一条通道
- 聚合结果缓存命中后直接 `return`：旧缓存里歌单恒为空，连补一次请求的机会都没有

现在：

1. **全站搜索统一 `/cloudsearch` 优先**：单曲 / 专辑 / 歌手 / 歌单 / 一起听搜歌单，以及综合流式搜索、分类翻页，全部先请求 `https://mapi.myocd.de5.net/cloudsearch?keywords=…&type=…&limit=…&offset=…`；只有 code 非 200、超时或对应结果数组为空时才退回 `/search`，行为不退化为比旧版更差。
2. **歌单解析兼容三种容器**：`result.playlists` / `data.playlists` / 顶层 `playlists` 都吃，按 id 去重；封面补 `imgUrl`、曲目数补 `count`、副标题补播放量。
3. **不带 cookie、走搜索专用客户端**：cloudsearch 按文档不需要登录，不再拼 4KB 凭证；同步与异步两条通道都统一，歌单首屏和「歌单」分类触底翻页改走异步可取消版本，不再占 IO 线程阻塞。
4. **缓存升 `v2` 键**：让「歌单恒为空」的旧缓存失效；即便命中缓存，歌单为空时仍单独补一次 cloudsearch 歌单请求，双保险。
5. **一起听「搜索歌单」同样改为 cloudsearch 优先、空结果回退 `/search`**。

上版的搜索缓存（10 分钟 / LRU 20 条）、本地曲库先亮、三路流式首屏、分类翻页去重都保留。

## 说明与限制
本次附带的 25 张专辑封面图与 4 个接口链接的资源解析全部失败；`mapi.myocd.de5.net` 在沙盒返回 403，**cloudsearch 的真实返回结构与歌单结果未做实机联调**，以 CI 编译＋签名校验为准。回退逻辑是「cloudsearch 6 秒超时或空结果才打 /search」，最坏情况下这一路会多等一次 6 秒（正常一次即返回）；若服务端对 `type=1000` 的返回字段名与 `result.playlists` 不同，已做三容器兼容但仍可能解析不到，届时该板块会显示为空并可用老接口兜底。
