package de.myocd.gomusic

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * 全局图片缓存：默认 ImageLoader 的磁盘缓存很小，滑动列表时封面会被反复重新下载/解码，
 * 这里给足内存 + 磁盘缓存，二次浏览直接命中。
 */
class GoMusicApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(150L * 1024 * 1024)
                .build()
        }
        .crossfade(false)
        // 图片请求也走同一个客户端：IPv4 优先 + 宽松超时 + 自动重试，
        // 避免弱网下封面一个个失败（API 接口已经有这些保护，图片此前没有）
        .okHttpClient { Api.client }
        .build()
}
