package de.myocd.gomusic

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 26.11.88：应用内统一网页容器。
 *
 * 所有需要打开 HTTP(S) 链接的地方（私信链接、登录安全验证、更新下载页等）
 * 都走这里，不再调系统浏览器。UA 与 cookie 强制固定：
 * - UA 固定为网易云安卓客户端串（易盾滑块 / 部分 H5 会按 UA 判断环境）
 * - cookie 固定为当前登录账号的 cookie；未登录则取游客 cookie
 * - 每次打开先清掉 WebView 里残留的其它账号 cookie，再逐条注入
 */
const val FIXED_WEB_UA =
    "com.netease.cloudmusic/9006035 (Linux; U; Android 14; zh_CN_#Hans; HEY2-W09; Build/HONORHEY2-W09; Cronet/72.0.3626.122)"

/** 把 "a=1; b=2" 逐条写进目标域及其父域，保证 H5 拿到的就是指定账号 */
private fun injectCookies(cm: CookieManager, url: String, cookie: String) {
    val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
        .ifBlank { "music.163.com" }
    val bare = host.trimStart('.')
    val domains = linkedSetOf(
        host, ".$bare",
        "music.163.com", ".music.163.com",
        "st.music.163.com", ".st.music.163.com",
        "163.com", ".163.com",
        "mapi.myocd.de5.net", ".myocd.de5.net",
        "reg.163.com", ".reg.163.com",
    )
    val attrs = setOf("path", "domain", "expires", "max-age", "secure", "httponly", "samesite")
    cookie.split(';')
        .map { it.trim() }
        .filter { kv ->
            kv.contains('=') && kv.substringBefore('=').trim().lowercase() !in attrs
        }
        .forEach { kv ->
            domains.forEach { d ->
                runCatching { cm.setCookie(d, "$kv; Domain=$d; Path=/") }
            }
        }
    cm.flush()
}

/** 清空残留 → 注入固定 cookie → 主线程加载。removeAllCookies 是异步的，必须等回调 */
private fun prepareAndLoad(web: WebView, url: String, cookie: String) {
    val cm = CookieManager.getInstance()
    cm.setAcceptCookie(true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        cm.setAcceptThirdPartyCookies(web, true)
    }
    cm.removeAllCookies {
        web.post {
            injectCookies(cm, url, cookie)
            web.loadUrl(url)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun setupWebView(web: WebView, onProgress: (Int) -> Unit, onTitle: (String) -> Unit): WebView =
    web.apply {
        settings.apply {
            userAgentString = FIXED_WEB_UA
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = false
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                // 再固定一次，防止个别页面通过 JS 改 UA 后跳转
                view.settings.userAgentString = FIXED_WEB_UA
                super.onPageStarted(view, url, favicon)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest,
            ): Boolean = handleScheme(view, request.url)

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleScheme(view, Uri.parse(url))

            private fun handleScheme(view: WebView, u: Uri): Boolean =
                when (u.scheme?.lowercase()) {
                    "http", "https" -> false   // 留在应用内 WebView
                    else -> {                  // tel: / weixin: / intent: 等交系统
                        runCatching {
                            view.context.startActivity(
                                Intent(Intent.ACTION_VIEW, u)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        true
                    }
                }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                onProgress(newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String) {
                onTitle(title)
            }
        }
        // 下载类链接（APK 等）不是「浏览页面」，交给系统下载/安装
        setDownloadListener(DownloadListener { u, _, _, _, _ ->
            runCatching {
                web.context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(u))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        })
    }

@Composable
fun WebLinkPage(vm: MainViewModel, url: String) {
    val go = LocalGo.current
    val ctx = LocalContext.current
    var webRef by remember { mutableStateOf<WebView?>(null) }
    var pct by remember { mutableStateOf(0) }
    var pageTitle by remember { mutableStateOf("") }
    var loadedKey by remember { mutableStateOf("") }

    // 页面内回退优先；回退到头就关掉整个网页页
    BackHandler(enabled = true) {
        val w = webRef
        if (w?.canGoBack() == true) w.goBack()
        else vm.closeWebLink()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().background(go.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GoButton("‹ 返回") { vm.closeWebLink() }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pageTitle.ifBlank { "网页" }, fontSize = 14.sp, color = go.on, maxLines = 1,
                )
                Text(
                    runCatching { Uri.parse(url).host }.getOrNull().orEmpty().ifBlank { url },
                    fontSize = 11.sp, color = go.muted, maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            GoButton("刷新") { webRef?.reload() }
            Spacer(Modifier.width(8.dp))
            GoButton("复制链接") {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("链接", webRef?.url ?: url))
                vm.toast("链接已复制")
            }
        }
        if (pct in 1..99) {
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = go.primary,
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                setupWebView(WebView(c), { pct = it }, { pageTitle = it })
                    .also { webRef = it }
            },
            update = { w ->
                webRef = w
                // cookie 到位（vm.webCookie 非 null）才注入并加载；key 防重复
                val ck = vm.webCookie ?: return@AndroidView
                val key = "$url|$ck"
                if (key != loadedKey) {
                    loadedKey = key
                    prepareAndLoad(w, url, ck)
                }
            },
        )
    }
}
